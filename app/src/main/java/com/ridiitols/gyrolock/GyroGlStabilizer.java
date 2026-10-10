package com.ridiitols.gyrolock;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GPU based video stabilization. Decodes to a SurfaceTexture, applies per-frame
 * gyro corrections with OpenGL ES, encodes through the Android MediaCodec surface
 * encoder, then remuxes the original audio track without re-encoding it.
 */
final class GyroGlStabilizer {
    interface Progress { void onProgress(String message); }
    private static final String MIME_AVC = "video/avc";
    private static final long CODEC_TIMEOUT_US = 10_000;

    private GyroGlStabilizer() {}

    static void stabilize(Context context, File input, File silentOutput, File finalOutput,
                          float[] times, float[] corrX, float[] corrY, float[] corrZ,
                          Progress progress) throws Exception {
        if (times == null || corrX == null || corrY == null || corrZ == null || times.length < 2
                || corrX.length != times.length || corrY.length != times.length || corrZ.length != times.length) {
            throw new IOException("Lintasan gyro 240 titik tidak valid");
        }
        if (progress != null) progress.onProgress("GPU: menyiapkan decoder dan encoder...");
        int rotation = readRotation(input);
        int outputRotation = transcodeWithGl(input, silentOutput, rotation, times, corrX, corrY, corrZ, progress);
        if (progress != null) progress.onProgress("Audio: menggabungkan audio asli...");
        // Keep encoded pixel dimensions unchanged and preserve only the appropriate
        // container orientation hint. This avoids rotating the pixels and then
        // rotating them a second time in the video player.
        remuxOriginalAudio(input, silentOutput, finalOutput, outputRotation);
        if (!finalOutput.exists() || finalOutput.length() < 1024) {
            throw new IOException("File hasil stabilisasi kosong atau terlalu kecil");
        }
        // Only remove the silent intermediate after the final mux completed.
        // The original input is never modified or deleted.
        //noinspection ResultOfMethodCallIgnored
        silentOutput.delete();
    }

    private static int readRotation(File input) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(input.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return value == null ? 0 : (Integer.parseInt(value) % 360 + 360) % 360;
        } catch (Exception ignored) {
            return 0;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private static int transcodeWithGl(File input, File output, int rotation, float[] times,
                                       float[] corrX, float[] corrY, float[] corrZ,
                                       Progress progress) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null, encoder = null;
        SurfaceTexture surfaceTexture = null;
        Surface decoderSurface = null, encoderSurface = null;
        EglWindow egl = null;
        GlRenderer renderer = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        int videoTrack = -1, muxerVideoTrack = -1;
        int outputRotation = 0;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            int track = findTrack(extractor, "video/");
            if (track < 0) throw new IOException("Video tidak ditemukan di file asli");
            extractor.selectTrack(track);
            MediaFormat inFormat = extractor.getTrackFormat(track);
            String mime = inFormat.getString(MediaFormat.KEY_MIME);
            int sourceWidth = inFormat.getInteger(MediaFormat.KEY_WIDTH);
            int sourceHeight = inFormat.getInteger(MediaFormat.KEY_HEIGHT);
            if ((sourceWidth & 1) != 0) sourceWidth--;
            if ((sourceHeight & 1) != 0) sourceHeight--;
            if (sourceWidth < 2 || sourceHeight < 2) throw new IOException("Resolusi video tidak valid");

            // Resolve orientation from the selected video track as well as
            // MediaMetadataRetriever. Some MP4 display-matrix files report 0/null via
            // MediaMetadataRetriever while MediaExtractor exposes rotation-degrees.
            // Prefer a non-zero track rotation when present, then normalize pixels in GL
            // and write the final file without a second rotation hint.
            int reportedRotation = normalizeRotation(rotation);
            try {
                if (inFormat.containsKey("rotation-degrees")) {
                    int trackRotation = normalizeRotation(inFormat.getInteger("rotation-degrees"));
                    if (trackRotation != 0) reportedRotation = trackRotation;
                }
            } catch (RuntimeException ignored) {}
            int width = sourceWidth;
            int height = sourceHeight;
            if (reportedRotation == 90 || reportedRotation == 270) {
                width = sourceHeight;
                height = sourceWidth;
            }
            outputRotation = 0;
            if ((width & 1) != 0) width--;
            if ((height & 1) != 0) height--;
            if (width < 2 || height < 2) throw new IOException("Ukuran output video tidak valid");

            MediaFormat encFormat = MediaFormat.createVideoFormat(MIME_AVC, width, height);
            encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            long pixels = (long) width * height;
            int bitrate = (int) Math.max(3_000_000L, Math.min(18_000_000L, pixels * 4L));
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            encoder = MediaCodec.createEncoderByType(MIME_AVC);
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            egl = new EglWindow(encoderSurface);
            renderer = new GlRenderer(width, height, reportedRotation, sourceWidth, sourceHeight);
            encoder.start();

            surfaceTexture = new SurfaceTexture(renderer.textureId());
            surfaceTexture.setDefaultBufferSize(sourceWidth, sourceHeight);
            AtomicReference<CountDownLatch> frameSignal = new AtomicReference<>();
            surfaceTexture.setOnFrameAvailableListener(st -> {
                CountDownLatch latch = frameSignal.get();
                if (latch != null) latch.countDown();
            });
            decoderSurface = new Surface(surfaceTexture);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inFormat, decoderSurface, null, 0);
            decoder.start();

            // Do NOT attach rotation metadata to this intermediate file. The final
            // muxer applies the hint exactly once after audio is remuxed.
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, decoderDone = false, encoderDone = false;
            long frameCount = 0;
            long lastProgressMs = System.currentTimeMillis();

            while (!encoderDone) {
                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        if (inputBuffer == null) throw new IOException("Buffer input decoder tidak tersedia");
                        inputBuffer.clear();
                        int size = extractor.readSampleData(inputBuffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputIndex, 0, size, Math.max(0, pts), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outIndex >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size > 0) {
                        long ptsUs = Math.max(0L, info.presentationTimeUs);
                        CountDownLatch latch = new CountDownLatch(1);
                        frameSignal.set(latch);
                        decoder.releaseOutputBuffer(outIndex, true);
                        if (!latch.await(2, TimeUnit.SECONDS)) {
                            frameSignal.set(null);
                            throw new IOException("Timeout menunggu frame decoder di GPU");
                        }
                        surfaceTexture.updateTexImage();
                        float[] textureMatrix = new float[16];
                        surfaceTexture.getTransformMatrix(textureMatrix);
                        float seconds = ptsUs / 1_000_000f;
                        float x = interpolate(times, corrX, seconds);
                        float y = interpolate(times, corrY, seconds);
                        float z = interpolate(times, corrZ, seconds);
                        egl.makeCurrent();
                        renderer.draw(textureMatrix, x, y, z);
                        EGLExt.eglPresentationTimeANDROID(egl.display, egl.surface, ptsUs * 1000L);
                        if (!EGL14.eglSwapBuffers(egl.display, egl.surface)) {
                            throw new IOException("GPU gagal mengirim frame ke encoder");
                        }
                        frameCount++;
                    } else {
                        decoder.releaseOutputBuffer(outIndex, false);
                    }
                    if (eos) {
                        decoderDone = true;
                        encoder.signalEndOfInputStream();
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Decoder output is rendered to SurfaceTexture; no raw format is needed.
                }

                while (true) {
                    int encIndex = encoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                    if (encIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw new IOException("Format encoder berubah setelah muxer dimulai");
                        MediaFormat outputFormat = encoder.getOutputFormat();
                        muxerVideoTrack = muxer.addTrack(outputFormat);
                        muxer.start();
                        muxerStarted = true;
                    } else if (encIndex >= 0) {
                        ByteBuffer encoded = encoder.getOutputBuffer(encIndex);
                        if (encoded == null) throw new IOException("Buffer output encoder tidak tersedia");
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                        if (info.size > 0) {
                            if (!muxerStarted) throw new IOException("Encoder belum mengirim format output");
                            encoded.position(info.offset);
                            encoded.limit(info.offset + info.size);
                            muxer.writeSampleData(muxerVideoTrack, encoded, info);
                        }
                        boolean encEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        encoder.releaseOutputBuffer(encIndex, false);
                        if (encEos) { encoderDone = true; break; }
                    }
                }
                long nowMs = System.currentTimeMillis();
                if (progress != null && nowMs - lastProgressMs > 1200) {
                    final long shown = frameCount;
                    progress.onProgress("GPU: memproses frame " + shown + "...");
                    lastProgressMs = nowMs;
                }
                if (decoderDone && !encoderDone) {
                    // Encoder EOS is drained in the normal output loop above.
                }
            }
            if (!muxerStarted || frameCount == 0) throw new IOException("Encoder tidak menghasilkan frame video");
        } finally {
            try { if (decoder != null) decoder.stop(); } catch (Exception ignored) {}
            try { if (decoder != null) decoder.release(); } catch (Exception ignored) {}
            try { if (surfaceTexture != null) surfaceTexture.release(); } catch (Exception ignored) {}
            try { if (decoderSurface != null) decoderSurface.release(); } catch (Exception ignored) {}
            try { if (renderer != null) renderer.release(); } catch (Exception ignored) {}
            try { if (egl != null) egl.release(); } catch (Exception ignored) {}
            try { if (encoder != null) encoder.stop(); } catch (Exception ignored) {}
            try { if (encoder != null) encoder.release(); } catch (Exception ignored) {}
            try { if (encoderSurface != null) encoderSurface.release(); } catch (Exception ignored) {}
            try { if (muxer != null) muxer.stop(); } catch (Exception ignored) {}
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            try { extractor.release(); } catch (Exception ignored) {}
        }
        return outputRotation;
    }

    private static int normalizeRotation(int value) {
        int r = ((value % 360) + 360) % 360;
        return (r == 0 || r == 90 || r == 180 || r == 270) ? r : 0;
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static float interpolate(float[] times, float[] values, float time) {
        if (time <= times[0]) return values[0];
        int last = times.length - 1;
        if (time >= times[last]) return values[last];
        int lo = 0, hi = last;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= time) lo = mid; else hi = mid;
        }
        float span = times[hi] - times[lo];
        if (span <= 0f) return values[lo];
        float f = (time - times[lo]) / span;
        return values[lo] + (values[hi] - values[lo]) * f;
    }

    private static void remuxOriginalAudio(File original, File silentVideo, File output, int rotation) throws IOException {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            videoExtractor.setDataSource(silentVideo.getAbsolutePath());
            int vTrack = findTrack(videoExtractor, "video/");
            if (vTrack < 0) throw new IOException("Video hasil GPU tidak ditemukan");
            videoExtractor.selectTrack(vTrack);
            MediaFormat vFormat = videoExtractor.getTrackFormat(vTrack);

            audioExtractor.setDataSource(original.getAbsolutePath());
            int aTrack = findTrack(audioExtractor, "audio/");
            MediaFormat aFormat = null;
            if (aTrack >= 0) {
                audioExtractor.selectTrack(aTrack);
                aFormat = audioExtractor.getTrackFormat(aTrack);
            }

            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            // Apply the source display orientation exactly once, to the FINAL file.
            if (rotation == 90 || rotation == 180 || rotation == 270) muxer.setOrientationHint(rotation);
            int outVideo = muxer.addTrack(vFormat);
            int outAudio = aFormat == null ? -1 : muxer.addTrack(aFormat);
            muxer.start();
            started = true;

            ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean videoDone = false, audioDone = aTrack < 0;
            while (!videoDone || !audioDone) {
                long vt = videoDone ? Long.MAX_VALUE : videoExtractor.getSampleTime();
                long at = audioDone ? Long.MAX_VALUE : audioExtractor.getSampleTime();
                if (vt < 0) { videoDone = true; vt = Long.MAX_VALUE; }
                if (at < 0) { audioDone = true; at = Long.MAX_VALUE; }
                if (videoDone && audioDone) break;
                boolean takeVideo = vt <= at;
                MediaExtractor chosen = takeVideo ? videoExtractor : audioExtractor;
                int destinationTrack = takeVideo ? outVideo : outAudio;
                buffer.clear();
                int size = chosen.readSampleData(buffer, 0);
                if (size < 0) {
                    if (takeVideo) videoDone = true; else audioDone = true;
                    continue;
                }
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = Math.max(0, chosen.getSampleTime());
                info.flags = chosen.getSampleFlags();
                muxer.writeSampleData(destinationTrack, buffer, info);
                chosen.advance();
            }
        } catch (RuntimeException e) {
            throw new IOException("Gagal menggabungkan audio/video: " + e.getMessage(), e);
        } finally {
            try { if (started && muxer != null) muxer.stop(); } catch (Exception ignored) {}
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            try { videoExtractor.release(); } catch (Exception ignored) {}
            try { audioExtractor.release(); } catch (Exception ignored) {}
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] data = new byte[256 * 1024];
            int n;
            while ((n = in.read(data)) >= 0) out.write(data, 0, n);
            out.flush();
        }
    }

    private static final class EglWindow {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;
        android.opengl.EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        android.opengl.EGLContext context = EGL14.EGL_NO_CONTEXT;
        android.opengl.EGLSurface surface = EGL14.EGL_NO_SURFACE;
        private final Surface nativeSurface;
        EglWindow(Surface nativeSurface) throws IOException {
            this.nativeSurface = nativeSurface;
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) throw new IOException("EGL display tidak tersedia");
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IOException("EGL gagal diinisialisasi");
            int[] configAttrs = { EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE };
            android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, configs.length, count, 0) || count[0] == 0)
                throw new IOException("Konfigurasi EGL encoder tidak tersedia");
            int[] contextAttrs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttrs, 0);
            if (context == EGL14.EGL_NO_CONTEXT) throw new IOException("EGL context gagal dibuat");
            int[] surfaceAttrs = { EGL14.EGL_NONE };
            surface = EGL14.eglCreateWindowSurface(display, configs[0], nativeSurface, surfaceAttrs, 0);
            if (surface == EGL14.EGL_NO_SURFACE) throw new IOException("EGL surface encoder gagal dibuat");
            makeCurrent();
        }
        void makeCurrent() throws IOException {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new IOException("EGL makeCurrent gagal");
        }
        void release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(display);
            }
            display = EGL14.EGL_NO_DISPLAY; surface = EGL14.EGL_NO_SURFACE; context = EGL14.EGL_NO_CONTEXT;
        }
    }

    private static final class GlRenderer {
        private static final String VERTEX =
                "attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; " +
                "void main(){ gl_Position=aPosition; vTexCoord=aTexCoord; }";
        private static final String FRAGMENT =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; uniform samplerExternalOES uTexture; uniform mat4 uTexMatrix; " +
                "uniform float uAngle; uniform vec2 uOffset; uniform float uCrop; uniform float uVideoRotation; " +
                "uniform vec2 uInvSize; varying vec2 vTexCoord; " +
                "vec2 mirrorUv(vec2 p){ return vec2(1.0)-abs(mod(p,2.0)-vec2(1.0)); } " +
                // Rotation metadata is clockwise; map output coordinates back to the source.
                "vec2 orientUv(vec2 p){ " +
                // Inverse-map output portrait coordinates back into the encoded source.
                // A 90-degree display rotation requires the opposite mapping when sampling.
                "if(uVideoRotation>45.0 && uVideoRotation<135.0) return vec2(p.y,1.0-p.x); " +
                "if(uVideoRotation>135.0 && uVideoRotation<225.0) return vec2(1.0-p.x,1.0-p.y); " +
                "if(uVideoRotation>225.0 && uVideoRotation<315.0) return vec2(1.0-p.y,p.x); return p; } " +
                "vec4 sampleMirror(vec2 p){ vec2 uv=mirrorUv(orientUv(p)); uv=(uTexMatrix*vec4(uv,0.0,1.0)).xy; return texture2D(uTexture,uv); } " +
                "void main(){ vec2 p=(vTexCoord-vec2(0.5))*uCrop; float c=cos(uAngle); float s=sin(uAngle); " +
                "p=mat2(c,-s,s,c)*p; vec2 warped=p+vec2(0.5)+uOffset; " +
                "vec4 sharp=sampleMirror(warped); vec2 d=uInvSize*2.0; " +
                "vec4 blur=sharp*0.40 + sampleMirror(warped+vec2(d.x,0.0))*0.15 + sampleMirror(warped-vec2(d.x,0.0))*0.15 + " +
                "sampleMirror(warped+vec2(0.0,d.y))*0.15 + sampleMirror(warped-vec2(0.0,d.y))*0.15; " +
                "float edge=max(max(-warped.x,warped.x-1.0),max(-warped.y,warped.y-1.0)); " +
                "float blurMix=smoothstep(0.0,0.08,edge); gl_FragColor=mix(sharp,blur,blurMix); }";
        private final int program;
        private final int texture;
        private final FloatBuffer vertices;
        private final FloatBuffer texCoords;
        private final int positionLoc, texLoc, matrixLoc, angleLoc, offsetLoc, cropLoc, rotationLoc, samplerLoc, invSizeLoc;
        private final float videoRotation;
        private final float inverseWidth, inverseHeight;
        GlRenderer(int width, int height, int videoRotation, int sourceWidth, int sourceHeight) throws IOException {
            this.videoRotation = videoRotation;
            this.inverseWidth = 1.0f / Math.max(1, sourceWidth);
            this.inverseHeight = 1.0f / Math.max(1, sourceHeight);
            float[] v = {-1f,-1f, 1f,-1f, -1f,1f, 1f,1f};
            float[] t = {0f,0f, 1f,0f, 0f,1f, 1f,1f};
            vertices = direct(v); texCoords = direct(t);
            program = createProgram(VERTEX, FRAGMENT);
            positionLoc = GLES20.glGetAttribLocation(program, "aPosition");
            texLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
            matrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");
            angleLoc = GLES20.glGetUniformLocation(program, "uAngle");
            offsetLoc = GLES20.glGetUniformLocation(program, "uOffset");
            cropLoc = GLES20.glGetUniformLocation(program, "uCrop");
            rotationLoc = GLES20.glGetUniformLocation(program, "uVideoRotation");
            samplerLoc = GLES20.glGetUniformLocation(program, "uTexture");
            invSizeLoc = GLES20.glGetUniformLocation(program, "uInvSize");
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0); texture = ids[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glViewport(0, 0, width, height);
        }
        int textureId() { return texture; }
        void draw(float[] texMatrix, float x, float y, float z) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            vertices.position(0); GLES20.glEnableVertexAttribArray(positionLoc);
            GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, vertices);
            texCoords.position(0); GLES20.glEnableVertexAttribArray(texLoc);
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texCoords);
            GLES20.glUniformMatrix4fv(matrixLoc, 1, false, texMatrix, 0);
            GLES20.glUniform1f(angleLoc, -z);
            GLES20.glUniform2f(offsetLoc, y * 0.35f, -x * 0.35f);
            GLES20.glUniform1f(cropLoc, 1.0f / 1.12f);
            GLES20.glUniform1f(rotationLoc, videoRotation);
            GLES20.glUniform2f(invSizeLoc, inverseWidth, inverseHeight);
            GLES20.glUniform1i(samplerLoc, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionLoc);
            GLES20.glDisableVertexAttribArray(texLoc);
            int err = GLES20.glGetError();
            if (err != GLES20.GL_NO_ERROR) throw new IllegalStateException("OpenGL error " + err);
        }
        void release() { GLES20.glDeleteTextures(1, new int[]{texture}, 0); GLES20.glDeleteProgram(program); }
        private static FloatBuffer direct(float[] values) {
            ByteBuffer bb = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = bb.asFloatBuffer(); fb.put(values).position(0); return fb;
        }
        private static int createProgram(String vertex, String fragment) throws IOException {
            int vs = compile(GLES20.GL_VERTEX_SHADER, vertex);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p);
            int[] status = new int[1]; GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
            GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs);
            if (status[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(p); GLES20.glDeleteProgram(p);
                throw new IOException("Shader OpenGL gagal: " + log);
            }
            return p;
        }
        private static int compile(int type, String source) throws IOException {
            int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
            int[] status = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader);
                throw new IOException("Shader compile gagal: " + log);
            }
            return shader;
        }
    }
}
