package com.gsmrelay.cloud.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

public class AudioRelayEngine {
    private static final String TAG = "AudioRelayEngine";

    public static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    // Silence Suppression (VAD) Threshold
    private static final float SILENCE_THRESHOLD = 0.008f;
    private static final int MAX_SILENCE_FRAMES = 12;

    public interface AudioListener {
        void onAudioCaptured(byte[] data, int offset, int length);
        void onTxLevel(float level);
        void onRxLevel(float level);
        default void onSourceSwitched(String sourceName, boolean locked) {}
    }

    private final Context context;
    private final AudioManager audioManager;
    private AudioListener listener;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;

    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isLoopbackTesting = new AtomicBoolean(false);
    private final AtomicBoolean isTestStreaming = new AtomicBoolean(false);
    private Thread recordThread;
    private Thread testStreamThread;

    private boolean isMuted = false;
    private boolean useSpeaker = true; // Default true: High sensitivity Acoustic Bridge
    private boolean isGateway = false;
    private boolean isPrivateRelay = false; // Default false: Acoustic Bridge transmits real GSM audio 100%
    private boolean isSilentGateway = false;
    private int customAudioSource = -1;
    private int currentActiveSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    private int[] activeCandidateList;
    private int currentCandidateIndex = 0;
    private float digitalGain = 5.0f; // High sensitivity digital pre-amp (+14dB boost)
    private float currentAgcGain = 5.0f;

    private long lastTxLevelTime = 0;
    private long lastRxLevelTime = 0;
    private int consecutiveSilenceFrames = 0;

    // Dynamic Echo Ducking & Anti-Feedback Voice Gate
    private volatile long lastIncomingSpeechTime = 0;
    private static final long ECHO_DUCKING_DURATION_MS = 380;

    // 2-pole Telephony Bandpass Filter (300Hz - 3400Hz at 16kHz)
    private float hpf_x1 = 0, hpf_x2 = 0, hpf_y1 = 0, hpf_y2 = 0;
    private float lpf_x1 = 0, lpf_x2 = 0, lpf_y1 = 0, lpf_y2 = 0;
    private static final float HPF_B0 = 0.9244f, HPF_B1 = -1.8488f, HPF_B2 = 0.9244f;
    private static final float HPF_A1 = -1.8414f, HPF_A2 = 0.8562f;
    private static final float LPF_B0 = 0.2452f, LPF_B1 = 0.4905f, LPF_B2 = 0.2452f;
    private static final float LPF_A1 = -0.3765f, LPF_A2 = 0.3575f;

    public AudioRelayEngine(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setRole(String role) {
        this.isGateway = "GATEWAY".equalsIgnoreCase(role);
        if (this.isGateway) {
            this.digitalGain = 5.0f; // High sensitivity digital pre-amp (+14dB boost for crystal clear audio)
            this.useSpeaker = !isPrivateRelay;
        } else {
            this.digitalGain = 2.5f;
            this.useSpeaker = true;
        }
    }

    public void setPrivateRelay(boolean privateRelay) {
        this.isPrivateRelay = privateRelay;
        this.isSilentGateway = privateRelay;
        this.useSpeaker = !privateRelay;
        configureAudioMode(isPlaying.get() || isRecording.get());
    }

    public boolean isPrivateRelay() {
        return isPrivateRelay;
    }

    public void setSilentGateway(boolean silent) {
        setPrivateRelay(silent);
    }

    public boolean isSilentGateway() {
        return isPrivateRelay;
    }

    public void setCustomAudioSource(int source) {
        this.customAudioSource = source;
    }

    public void setAudioListener(AudioListener listener) {
        this.listener = listener;
    }

    public void setAudioBoost(boolean boost) {
        if (isGateway) {
            this.digitalGain = boost ? 6.0f : 4.0f;
        } else {
            this.digitalGain = boost ? 3.0f : 1.0f;
        }
    }

    public synchronized void startRelay(boolean speaker) {
        this.useSpeaker = speaker;
        this.isLoopbackTesting.set(false);
        configureAudioMode(true);
        startPlayback();
        startRecording();
    }

    public synchronized void startLoopbackTest() {
        this.useSpeaker = true;
        this.isLoopbackTesting.set(true);
        configureAudioMode(true);
        startPlayback();
        startRecording();
    }

    public synchronized void stopRelay() {
        this.isLoopbackTesting.set(false);
        stopAudioTestStream();
        stopRecording();
        stopPlayback();
        configureAudioMode(false);
    }

    public synchronized void startAudioTestStream() {
        if (isTestStreaming.get()) return;
        isTestStreaming.set(true);

        testStreamThread = new Thread(() -> {
            int sampleRate = SAMPLE_RATE;
            double phase = 0.0;
            // Harmonic chime sequence: C5 (523Hz), E5 (659Hz), G5 (784Hz), C6 (1046Hz), A5 (880Hz)
            double[] notes = {523.25, 659.25, 783.99, 1046.50, 880.00, 783.99};
            int noteIndex = 0;
            long noteStartTime = System.currentTimeMillis();
            byte[] frame = new byte[1024]; // 512 samples = 32ms frame

            Log.i(TAG, "Audio test stream started - transmitting test tones to cloud");

            while (isTestStreaming.get()) {
                long now = System.currentTimeMillis();
                if (now - noteStartTime > 450) {
                    noteIndex = (noteIndex + 1) % notes.length;
                    noteStartTime = now;
                }

                double freq = notes[noteIndex];
                double angularFreq = 2.0 * Math.PI * freq / sampleRate;

                for (int i = 0; i < 512; i++) {
                    phase += angularFreq;
                    if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;

                    double decay = Math.max(0.08, 1.0 - ((now - noteStartTime) / 450.0));
                    double sampleVal = (Math.sin(phase) * 0.75 + Math.sin(phase * 2.0) * 0.25) * decay;
                    short pcmSample = (short) (sampleVal * 19000);

                    frame[i * 2] = (byte) (pcmSample & 0xFF);
                    frame[i * 2 + 1] = (byte) ((pcmSample >> 8) & 0xFF);
                }

                if (listener != null) {
                    listener.onAudioCaptured(frame, 0, frame.length);
                    float level = computeAudioLevel(frame, 0, frame.length);
                    listener.onTxLevel(level);
                }

                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    break;
                }
            }
            Log.i(TAG, "Audio test stream terminated");
        }, "GsmAudioTestStreamThread");
        testStreamThread.setPriority(Thread.NORM_PRIORITY + 1);
        testStreamThread.start();
    }

    public synchronized void stopAudioTestStream() {
        isTestStreaming.set(false);
        if (testStreamThread != null) {
            try { testStreamThread.interrupt(); } catch (Throwable ignored) {}
            testStreamThread = null;
        }
    }

    public boolean isAudioTestStreaming() {
        return isTestStreaming.get();
    }

    public void setBluetoothSco(boolean enable) {
        if (audioManager == null) return;
        try {
            if (enable) {
                audioManager.startBluetoothSco();
                if (audioManager.isBluetoothScoAvailableOffCall() || audioManager.isBluetoothA2dpOn()) {
                    audioManager.setBluetoothScoOn(true);
                }
                Log.i(TAG, "Bluetooth SCO audio routing requested");
            } else {
                audioManager.setBluetoothScoOn(false);
                audioManager.stopBluetoothSco();
                Log.i(TAG, "Bluetooth SCO audio routing stopped");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error toggling Bluetooth SCO", t);
        }
    }

    public boolean isLoopbackActive() {
        return isLoopbackTesting.get();
    }

    public void setSpeaker(boolean enable) {
        this.useSpeaker = enable;
        if (audioManager != null) {
            try {
                audioManager.setSpeakerphoneOn(enable);
            } catch (Throwable ignored) {}
        }
    }

    public void setMuted(boolean mute) {
        this.isMuted = mute;
    }

    public boolean isMuted() {
        return isMuted;
    }

    private void configureAudioMode(boolean active) {
        if (audioManager == null) return;
        try {
            if (active) {
                if (isGateway) {
                    if (isPrivateRelay || isSilentGateway) {
                        if (audioManager.isBluetoothScoOn()) {
                            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                            audioManager.setSpeakerphoneOn(false);
                            Log.i(TAG, "🎧 Silent Gateway Mode with Bluetooth SCO active");
                        } else {
                            audioManager.setSpeakerphoneOn(false);
                            int minVol = 1;
                            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, minVol, 0);
                            Log.i(TAG, "🔒 Silent Private Gateway Mode ACTIVE: Speakerphone OFF, volume=1");
                        }
                    } else {
                        // Smart Acoustic Loudspeaker Bridge:
                        // Use MODE_IN_COMMUNICATION so Android AudioPolicy gives full capture stream without HAL muting
                        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                        audioManager.setSpeakerphoneOn(true);
                        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                        int targetVol = (int) (maxVol * 0.75f);
                        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, Math.max(1, targetVol), 0);
                        Log.i(TAG, "🔊 Smart Acoustic Loudspeaker Bridge active: speakerphone ON (vol=" + targetVol + "/" + maxVol + ")");
                    }
                } else {
                    // Terminal: VoIP communication mode
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    audioManager.setSpeakerphoneOn(useSpeaker);
                }
                audioManager.setMicrophoneMute(false);
            } else {
                audioManager.setMicrophoneMute(false);
                audioManager.setSpeakerphoneOn(false);
                audioManager.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error configuring audio mode", e);
        }
    }

    private void startRecording() {
        if (isRecording.get()) return;

        try {
            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING);
            int bufferSize = Math.max(minBufferSize, 2048);

            // Candidate audio sources:
            if (customAudioSource >= 0) {
                activeCandidateList = new int[]{
                        customAudioSource,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        9,
                        MediaRecorder.AudioSource.MIC,
                        MediaRecorder.AudioSource.CAMCORDER,
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION
                };
            } else if (isGateway) {
                if (isPrivateRelay || isSilentGateway) {
                    activeCandidateList = new int[]{
                            4, // Direct cellular DSP (rooted / privileged)
                            3, // Direct downlink DSP
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            9,
                            MediaRecorder.AudioSource.MIC,
                            MediaRecorder.AudioSource.DEFAULT
                    };
                } else {
                    // Smart Acoustic Loudspeaker Bridge (CDD guaranteed no AEC / zeroing!):
                    activeCandidateList = new int[]{
                            MediaRecorder.AudioSource.VOICE_RECOGNITION, // 6: CDD standard, no echo cancellation
                            9,                                           // MediaRecorder.AudioSource.UNPROCESSED: Raw PCM
                            MediaRecorder.AudioSource.MIC,               // 1: Primary mic
                            MediaRecorder.AudioSource.CAMCORDER,         // 5: Directional top mic
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION,// 7: Fallback
                            MediaRecorder.AudioSource.DEFAULT            // 0: Default
                    };
                }
            } else {
                activeCandidateList = new int[]{
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.MIC
                };
            }

            currentCandidateIndex = 0;
            currentActiveSource = activeCandidateList[0];

            for (int i = 0; i < activeCandidateList.length; i++) {
                int source = activeCandidateList[i];
                try {
                    audioRecord = new AudioRecord(source, SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize);
                    if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                        currentActiveSource = source;
                        currentCandidateIndex = i;
                        Log.i(TAG, "AudioRecord successfully initialized with source: " + getSourceName(source));
                        break;
                    } else {
                        audioRecord.release();
                        audioRecord = null;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Source " + getSourceName(source) + " unavailable: " + t.getMessage() + ", trying next...");
                }
            }

            if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed on all sources");
                return;
            }

            try {
                int sessionId = audioRecord.getAudioSessionId();
                if (!isGateway && AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(sessionId);
                    if (echoCanceler != null) echoCanceler.setEnabled(true);
                }
                if (!isGateway && NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId);
                    if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
                }
            } catch (Throwable ignored) {}

            audioRecord.startRecording();
            isRecording.set(true);
            consecutiveSilenceFrames = 0;

            recordThread = new Thread(() -> {
                byte[] buffer = new byte[1024]; // 64ms frame (512 samples)
                int silenceCounter = 0;
                boolean sourceConfirmed = false;

                while (isRecording.get()) {
                    try {
                        AudioRecord currentRecord = audioRecord;
                        if (currentRecord == null || currentRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                            try { Thread.sleep(25); } catch (InterruptedException e) { break; }
                            continue;
                        }

                        int bytesRead = currentRecord.read(buffer, 0, buffer.length);
                        if (bytesRead > 0) {
                            // Measure peak amplitude to detect digital zeroing by vendor HAL
                            int peakAmp = 0;
                            int sampleCount = bytesRead / 2;
                            for (int i = 0; i < sampleCount; i++) {
                                short sample = (short) ((buffer[i * 2] & 0xFF) | (buffer[i * 2 + 1] << 8));
                                int abs = Math.abs(sample);
                                if (abs > peakAmp) peakAmp = abs;
                            }

                            // Dynamic Self-Healing Source Prober on Gateway:
                            if (isGateway && !sourceConfirmed && activeCandidateList != null && activeCandidateList.length > 1) {
                                if (peakAmp < 15) {
                                    silenceCounter++;
                                    // If 25 consecutive frames (~800ms) are pure silence, hot-swap to next candidate!
                                    if (silenceCounter >= 25) {
                                        silenceCounter = 0;
                                        hotSwapNextAudioSource();
                                        continue;
                                    }
                                } else if (peakAmp >= 35) {
                                    silenceCounter = 0;
                                    sourceConfirmed = true;
                                    Log.i(TAG, "✅ Audio source CONFIRMED active with signal: " + getSourceName(currentActiveSource));
                                    if (listener != null) {
                                        listener.onSourceSwitched(getSourceName(currentActiveSource), true);
                                    }
                                }
                            }

                            // Dynamic Anti-Echo Ducking on Gateway:
                            // When Terminal user is speaking into Gateway speaker, duck Gateway mic capture
                            // to eliminate acoustic feedback howl and echo loop
                            boolean isDucking = false;
                            if (isGateway && (System.currentTimeMillis() - lastIncomingSpeechTime < ECHO_DUCKING_DURATION_MS)) {
                                isDucking = true;
                                attenuateBuffer(buffer, bytesRead, 0.06f); // -24dB ducking
                            }

                            // Telephony Bandpass Filter (300Hz - 3400Hz):
                            // Removes mechanical chassis vibrations (<300Hz) and high-pitch feedback (>3400Hz)
                            if (isGateway && !isDucking) {
                                applyTelephonyBandpassFilter(buffer, bytesRead);
                            }

                            // Apply Adaptive Automatic Gain Control in Private Mode, or static digital boost with soft limiter
                            if (isGateway && (isPrivateRelay || isSilentGateway)) {
                                applyAdaptiveGain(buffer, bytesRead);
                            } else if (digitalGain > 1.01f && !isDucking) {
                                applyGain(buffer, bytesRead, digitalGain);
                            }

                            float level = computeAudioLevel(buffer, bytesRead);
                            long now = System.currentTimeMillis();

                            if (now - lastTxLevelTime > 80) {
                                lastTxLevelTime = now;
                                if (listener != null) {
                                    listener.onTxLevel(isMuted ? 0f : level);
                                }
                            }

                            // If in self-test loopback mode, directly play through speaker!
                            if (isLoopbackTesting.get()) {
                                playIncomingAudio(buffer, 0, bytesRead);
                                continue;
                            }

                            // Streaming to network
                            if (isGateway) {
                                if (!isMuted && listener != null) {
                                    listener.onAudioCaptured(buffer, 0, bytesRead);
                                }
                            } else {
                                if (level < SILENCE_THRESHOLD) {
                                    consecutiveSilenceFrames++;
                                } else {
                                    consecutiveSilenceFrames = 0;
                                }

                                if (!isMuted && consecutiveSilenceFrames < MAX_SILENCE_FRAMES && listener != null) {
                                    listener.onAudioCaptured(buffer, 0, bytesRead);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Error in audio record thread", t);
                        break;
                    }
                }
            }, "GsmAudioRecordThread");
            recordThread.setPriority(Thread.NORM_PRIORITY + 1);
            recordThread.start();

        } catch (Throwable e) {
            Log.e(TAG, "Failed to start recording", e);
            stopRecording();
        }
    }

    private synchronized void hotSwapNextAudioSource() {
        if (!isRecording.get() || activeCandidateList == null || activeCandidateList.length <= 1) return;
        currentCandidateIndex = (currentCandidateIndex + 1) % activeCandidateList.length;
        int nextSource = activeCandidateList[currentCandidateIndex];
        Log.w(TAG, "🔄 Hot-swapping audio source to: " + getSourceName(nextSource));

        try {
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                    audioRecord.release();
                } catch (Throwable ignored) {}
                audioRecord = null;
            }

            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING);
            int bufferSize = Math.max(minBufferSize, 2048);

            audioRecord = new AudioRecord(nextSource, SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize);
            if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                audioRecord.startRecording();
                currentActiveSource = nextSource;
                Log.i(TAG, "✅ Hot-swapped successfully to: " + getSourceName(nextSource));
                if (listener != null) {
                    listener.onSourceSwitched(getSourceName(nextSource), false);
                }
            } else {
                audioRecord.release();
                audioRecord = null;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed hot-swapping to source " + getSourceName(nextSource), t);
        }
    }

    private String getSourceName(int source) {
        switch (source) {
            case 4: return "VOICE_CALL (Direct Baseband DSP #4)";
            case 3: return "VOICE_DOWNLINK (Cellular Downlink DSP #3)";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION (VoIP DSP #7)";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION (Pure Acoustic #6)";
            case 9: return "UNPROCESSED (Raw Direct PCM #9)";
            case MediaRecorder.AudioSource.CAMCORDER: return "CAMCORDER (Secondary Mic #5)";
            case MediaRecorder.AudioSource.MIC: return "MIC (Primary Mic #1)";
            default: return "Source(" + source + ")";
        }
    }

    private void attenuateBuffer(byte[] buffer, int length, float factor) {
        int sampleCount = length / 2;
        for (int i = 0; i < sampleCount; i++) {
            int idx = i * 2;
            short sample = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));
            short attenuated = (short) Math.round(sample * factor);
            buffer[idx] = (byte) (attenuated & 0xFF);
            buffer[idx + 1] = (byte) ((attenuated >> 8) & 0xFF);
        }
    }

    private synchronized void applyTelephonyBandpassFilter(byte[] buffer, int length) {
        int sampleCount = length / 2;
        for (int i = 0; i < sampleCount; i++) {
            int idx = i * 2;
            float in = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));

            // High-Pass Filter (cutoff ~280Hz at 16kHz)
            float hpf_out = HPF_B0 * in + HPF_B1 * hpf_x1 + HPF_B2 * hpf_x2 - HPF_A1 * hpf_y1 - HPF_A2 * hpf_y2;
            hpf_x2 = hpf_x1;
            hpf_x1 = in;
            hpf_y2 = hpf_y1;
            hpf_y1 = hpf_out;

            // Low-Pass Filter (cutoff ~3400Hz at 16kHz)
            float lpf_out = LPF_B0 * hpf_out + LPF_B1 * lpf_x1 + LPF_B2 * lpf_x2 - LPF_A1 * lpf_y1 - LPF_A2 * lpf_y2;
            lpf_x2 = lpf_x1;
            lpf_x1 = hpf_out;
            lpf_y2 = lpf_y1;
            lpf_y1 = lpf_out;

            int clamped = Math.round(lpf_out);
            if (clamped > 32767) clamped = 32767;
            else if (clamped < -32768) clamped = -32768;

            buffer[idx] = (byte) (clamped & 0xFF);
            buffer[idx + 1] = (byte) ((clamped >> 8) & 0xFF);
        }
    }

    // Adaptive Automatic Gain Control (AGC) with smooth soft cubic limiter
    private void applyAdaptiveGain(byte[] buffer, int length) {
        int sampleCount = length / 2;
        if (sampleCount == 0) return;

        int maxAmp = 0;
        for (int i = 0; i < sampleCount; i++) {
            int idx = i * 2;
            short sample = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));
            int abs = Math.abs(sample);
            if (abs > maxAmp) maxAmp = abs;
        }

        if (maxAmp > 150) { // Speech detected above noise floor
            float targetGain = 18000.0f / (float) maxAmp;
            // Bound target gain between 3.5f and 14.0f
            targetGain = Math.max(3.5f, Math.min(14.0f, targetGain));
            currentAgcGain = (currentAgcGain * 0.85f) + (targetGain * 0.15f);
        }

        for (int i = 0; i < sampleCount; i++) {
            int idx = i * 2;
            short sample = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));
            int amplified = Math.round(sample * currentAgcGain);
            // Soft cubic limiter to eliminate clipping and preserve audio clarity
            if (amplified > 28000) {
                amplified = 28000 + (amplified - 28000) / 4;
                if (amplified > 32767) amplified = 32767;
            } else if (amplified < -28000) {
                amplified = -28000 + (amplified + 28000) / 4;
                if (amplified < -32768) amplified = -32768;
            }
            buffer[idx] = (byte) (amplified & 0xFF);
            buffer[idx + 1] = (byte) ((amplified >> 8) & 0xFF);
        }
    }

    private void applyGain(byte[] buffer, int length, float gain) {
        int sampleCount = length / 2;
        for (int i = 0; i < sampleCount; i++) {
            int idx = i * 2;
            short sample = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));
            int amplified = Math.round(sample * gain);
            // Soft cubic limiter
            if (amplified > 28000) {
                amplified = 28000 + (amplified - 28000) / 4;
                if (amplified > 32767) amplified = 32767;
            } else if (amplified < -28000) {
                amplified = -28000 + (amplified + 28000) / 4;
                if (amplified < -32768) amplified = -32768;
            }
            buffer[idx] = (byte) (amplified & 0xFF);
            buffer[idx + 1] = (byte) ((amplified >> 8) & 0xFF);
        }
    }

    private void stopRecording() {
        isRecording.set(false);
        if (recordThread != null) {
            try { recordThread.interrupt(); } catch (Throwable ignored) {}
            recordThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Throwable ignored) {}
            audioRecord = null;
        }
        if (echoCanceler != null) {
            try { echoCanceler.release(); } catch (Throwable ignored) {}
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            try { noiseSuppressor.release(); } catch (Throwable ignored) {}
            noiseSuppressor = null;
        }
    }

    private void startPlayback() {
        if (isPlaying.get() && audioTrack != null) return;

        try {
            int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING);
            int bufferSize = Math.max(minBufferSize, 2048);

            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(ENCODING)
                    .setChannelMask(CHANNEL_OUT)
                    .build();

            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            audioTrack.play();
            isPlaying.set(true);

            // Ensure call audio volume is at a balanced clear level (75%)
            if (audioManager != null) {
                try {
                    int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                    int curVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
                    if (curVol < maxVol / 2) {
                        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (int) (maxVol * 0.75f), 0);
                    }
                } catch (Throwable ignored) {}
            }
            Log.i(TAG, "AudioTrack playback started successfully (16kHz PCM)");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to start AudioTrack playback", e);
            stopPlayback();
        }
    }

    public void playIncomingAudio(byte[] data, int offset, int length) {
        if (data == null || length <= 0) return;

        // Auto-start playback immediately if not yet running so no packet is ever lost!
        if (!isPlaying.get() || audioTrack == null) {
            startPlayback();
        }

        if (isPlaying.get() && audioTrack != null) {
            try {
                audioTrack.write(data, offset, length);

                float level = computeAudioLevel(data, offset, length);
                long now = System.currentTimeMillis();

                // Dynamic Echo Ducking: When Terminal voice plays out of speaker,
                // mark time so recording mic is ducked to prevent echo screech!
                if (level > 0.015f) {
                    lastIncomingSpeechTime = now;
                }

                if (now - lastRxLevelTime > 80) {
                    lastRxLevelTime = now;
                    if (listener != null) {
                        listener.onRxLevel(level);
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "Error writing to AudioTrack", e);
            }
        }
    }

    private void stopPlayback() {
        isPlaying.set(false);
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.flush();
                audioTrack.release();
            } catch (Throwable ignored) {}
            audioTrack = null;
        }
    }

    private float computeAudioLevel(byte[] buffer, int length) {
        return computeAudioLevel(buffer, 0, length);
    }

    private float computeAudioLevel(byte[] buffer, int offset, int length) {
        long sum = 0;
        int sampleCount = length / 2;
        if (sampleCount == 0) return 0f;

        for (int i = 0; i < sampleCount; i++) {
            int idx = offset + i * 2;
            short sample = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));
            sum += Math.abs(sample);
        }

        float avg = (float) sum / sampleCount;
        float normalized = avg / 32767.0f;
        return Math.min(1.0f, normalized * 3.5f);
    }
}
