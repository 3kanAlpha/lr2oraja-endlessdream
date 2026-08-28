package bms.player.beatoraja.audio;

import java.nio.ByteBuffer;
import java.nio.file.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bms.player.beatoraja.Config;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.portaudio.*;

/**
 * PortAudioドライバ
 * 
 * @author exch
 */
public class PortAudioDriver extends AbstractAudioDriver<PCM> implements Runnable {
	private static final Logger logger = LoggerFactory.getLogger(PortAudioDriver.class);
	private static final int WRITE_RETRY_COUNT = 1;
	private static final long REOPEN_RETRY_DELAY_MILLIS = 1000;

	private static DeviceInfo[] devices;
	
	private BlockingStream stream;
	private final Object streamLock = new Object();
	private final StreamParameters streamParameters;
	private final int framesPerBuffer;

	/**
	 * ミキサー入力
	 */
	private final MixerInput[] inputs;

	private long idcount;
	
	private volatile boolean stop = false;
	
	private final float[] buffer;
	
	private final Thread mixer;

	public static DeviceInfo[] getDevices() {
		if(devices == null) {
			PortAudio.initialize();
			
			devices = new DeviceInfo[PortAudio.getDeviceCount()];
			for(int i = 0;i < devices.length;i++) {
				devices[i] = PortAudio.getDeviceInfo(i);
			}
		}
		return devices;
	}

	public PortAudioDriver(Config config) {
		super(config.getSongResourceGen());
		DeviceInfo[] devices = getDevices();
		// Get the default device and setup the stream parameters.
		int deviceId = 0;
		for(int i = 0;i < devices.length;i++) {
			if(devices[i].name.equals(config.getAudioConfig().getDriverName())) {
				deviceId = i;
				break;
			}
		}
		DeviceInfo deviceInfo = devices[ deviceId ];
		
		setSampleRate(config.getAudioConfig().getSampleRate() <= 0 ? (int)deviceInfo.defaultSampleRate : config.getAudioConfig().getSampleRate());
		channels = 2;
//		System.out.println( "  deviceId    = " + deviceId );
//		System.out.println( "  sampleRate  = " + sampleRate );
//		System.out.println( "  device name = " + deviceInfo.name );

		streamParameters = new StreamParameters();
		streamParameters.channelCount = channels;
		streamParameters.device = deviceId;
		framesPerBuffer = config.getAudioConfig().getDeviceBufferSize();
		streamParameters.suggestedLatency = ((double)framesPerBuffer) / getSampleRate();
//		System.out.println( "  suggestedLatency = " + streamParameters.suggestedLatency );

		stream = openStream();

		mixer = new Thread(this);
		buffer = new float[framesPerBuffer * channels];
		inputs = new MixerInput[config.getAudioConfig().getDeviceSimultaneousSources()];
		for (int i = 0; i < inputs.length; i++) {
			inputs[i] = new MixerInput();
		}
		mixer.start();
	}

	@Override
	protected PCM getKeySound(Path p) {
		return PCM.load(p.toString(), this);
	}

	@Override
	protected PCM getKeySound(PCM pcm) {
		return pcm;
	}

	@Override
	protected void play(PCM pcm, int channel, float volume, float pitch) {
		put(pcm, channel, volume, pitch, false);
	}

	@Override
	protected void play(AudioElement<PCM> id, float volume, boolean loop) {
		id.id = put(id.audio, -1, volume, 1.0f, loop);
	}

	@Override
	protected void setVolume(AudioElement<PCM> id, float volume) {
		for (MixerInput input : inputs) {
			if (input.id == id.id) {
				input.volume = volume;
				break;
			}
		}
	}

	@Override
	protected void disposeKeySound(PCM pcm) {
		stop(pcm);
	}

	private long put(PCM pcm, int channel, float volume, float pitch, boolean loop) {
		synchronized (inputs) {
			for (MixerInput input : inputs) {
				if (input.pos == -1) {
					input.pcm = pcm;
					input.volume = volume;
					input.pitch = pitch;
					input.loop = loop;
					input.id = idcount++;
					input.channel = channel;
					input.startedAtNanos = System.nanoTime();
					input.pos = 0;
					input.posf = 0;
					return input.id;
				}
			}
		}
		return -1;
	}

	@Override
	protected boolean isPlaying(PCM id) {
		synchronized (inputs) {
			for (MixerInput input : inputs) {
				if (input.pcm == id) {
					return input.pos != -1;
				}
			}				
		}
		return false;
	}


	@Override
	protected void stop(PCM id) {
		synchronized (inputs) {
			for (MixerInput input : inputs) {
				if (input.pcm == id) {
					input.pos = -1;
				}
			}				
		}
	}

	@Override
	protected void stop(PCM id, int channel) {
		synchronized (inputs) {
			for (MixerInput input : inputs) {
				if (input.pcm == id && input.channel == channel) {
					input.pos = -1;
				}
			}
		}
	}

	@Override
	protected void setVolume(PCM id, int channel, float volume) {
		synchronized (inputs) {
			for (MixerInput input : inputs) {
				if (input.pcm == id && input.channel == channel) {
					input.volume = volume;
				}
			}
		}
	}

	public void run() {
		boolean comInitialized = Platform.isWindows()
				&& COMUtils.SUCCEEDED(Ole32.INSTANCE.CoInitialize(null));
		try {
			runMixer();
		} finally {
			try {
				synchronized (streamLock) {
					closeStream();
					PortAudio.terminate();
				}
			} finally {
				if (comInitialized) {
					Ole32.INSTANCE.CoUninitialize();
				}
			}
		}
	}

	private void runMixer() {
		while(!stop) {
			final float gpitch = getGlobalPitch();
			synchronized (inputs) {
				for (int i = 0; i < buffer.length; i+=2) {
					float wav_l = 0;
					float wav_r = 0;
					for (MixerInput input : inputs) {
						if (input.pos != -1) {
							if(input.pcm instanceof FloatPCM) {
								final float[] sample = (float[]) input.pcm.sample;
								wav_l += sample[input.pos + input.pcm.start] * input.volume;
								wav_r += sample[input.pos+1 + input.pcm.start] * input.volume;
							} else if(input.pcm instanceof ShortDirectPCM) {
								final ByteBuffer sample = (ByteBuffer) input.pcm.sample;
								wav_l += ((float) sample.getShort((input.pos + input.pcm.start) * 2)) * input.volume / Short.MAX_VALUE;
								wav_r += ((float) sample.getShort((input.pos+1 + input.pcm.start) * 2)) * input.volume / Short.MAX_VALUE;
							} else if(input.pcm instanceof ShortPCM) {
								final short[] sample = (short[]) input.pcm.sample;
								wav_l += ((float) sample[input.pos + input.pcm.start]) * input.volume / Short.MAX_VALUE;
								wav_r += ((float) sample[input.pos+1 + input.pcm.start]) * input.volume / Short.MAX_VALUE;
							} else if(input.pcm instanceof BytePCM) {
								final byte[] sample = (byte[]) input.pcm.sample;
								wav_l += ((float) (sample[input.pos + input.pcm.start] - 128)) * input.volume / Byte.MAX_VALUE;
								wav_r += ((float) (sample[input.pos+1 + input.pcm.start] - 128)) * input.volume / Byte.MAX_VALUE;
							}
							input.posf += gpitch * input.pitch;
							int inc = (int)input.posf;
							if (inc > 0) {
								input.pos += 2 * inc;
								input.posf -= (float)inc;
							}
							if (input.pos >= input.pcm.len) {
								input.pos = input.loop ? 0 : -1;
							}
						}
					}
					buffer[i] = wav_l;
					buffer[i+1] = wav_r;
				}
			}

			writeBuffer();
		}
	}

	private void writeBuffer() {
		long writeStartedAtNanos = System.nanoTime();
		int failures = 0;
		while (!stop) {
			try {
				stream.write(buffer, framesPerBuffer);
				if (failures > 0) {
					catchUpInputs(writeStartedAtNanos);
				}
				return;
			} catch (RuntimeException e) {
				logger.warn("PortAudio stream write failed", e);
			}

			if (shouldReopenStream(++failures)) {
				while (!stop && !reopenStream()) {
					try {
						Thread.sleep(REOPEN_RETRY_DELAY_MILLIS);
					} catch (InterruptedException e) {
						stop = true;
						Thread.currentThread().interrupt();
						return;
					}
				}
				catchUpInputs(writeStartedAtNanos);
				// The failed write may be partial, so resume with the next buffer instead of replaying it.
				return;
			}
		}
	}

	private void catchUpInputs(long writeStartedAtNanos) {
		long catchUpFromNanos = writeStartedAtNanos
				+ (long) framesPerBuffer * 1_000_000_000L / getSampleRate();
		float gpitch = getGlobalPitch();
		synchronized (inputs) {
			long now = System.nanoTime();
			for (MixerInput input : inputs) {
				if (input.pos != -1) {
					advanceInput(input, now - Math.max(catchUpFromNanos, input.startedAtNanos),
							getSampleRate(), gpitch, channels);
				}
			}
		}
	}

	static void advanceInput(MixerInput input, long elapsedNanos, int sampleRate, float globalPitch, int channels) {
		if (elapsedNanos <= 0) {
			return;
		}
		double frames = input.posf + elapsedNanos * (double) sampleRate / 1_000_000_000 * globalPitch * input.pitch;
		long frameAdvance = (long) frames;
		input.posf = (float) (frames - frameAdvance);
		long position = input.pos + frameAdvance * channels;
		if (position >= input.pcm.len) {
			input.pos = input.loop ? (int) (position % input.pcm.len) : -1;
		} else {
			input.pos = (int) position;
		}
	}

	static boolean shouldReopenStream(int consecutiveFailures) {
		return consecutiveFailures > WRITE_RETRY_COUNT;
	}

	private BlockingStream openStream() {
		BlockingStream openedStream = PortAudio.openStream(null, streamParameters, getSampleRate(), framesPerBuffer, 0);
		openedStream.start();
		return openedStream;
	}

	private boolean reopenStream() {
		synchronized (streamLock) {
			if (stop) {
				return false;
			}
			closeStream();
			try {
				stream = openStream();
				return true;
			} catch (RuntimeException e) {
				logger.warn("Failed to reopen PortAudio stream; retrying", e);
				return false;
			}
		}
	}

	private void closeStream() {
		if (stream == null) {
			return;
		}
		try {
			stream.abort();
		} catch (RuntimeException e) {
			logger.warn("Failed to abort PortAudio stream", e);
		}
		try {
			stream.close();
		} catch (RuntimeException e) {
			logger.warn("Failed to close PortAudio stream", e);
		}
		stream = null;
	}

	public void dispose() {
		synchronized (streamLock) {
			stop = true;
		}
		super.dispose();
		try {
			mixer.join(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		if (mixer.isAlive()) {
			synchronized (streamLock) {
				if (stream != null) {
					try {
						stream.abort();
					} catch (RuntimeException e) {
						logger.warn("Failed to abort PortAudio stream", e);
					}
				}
			}
			try {
				mixer.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (mixer.isAlive()) {
				logger.warn("PortAudio mixer thread did not stop");
			}
		}
	}

	static class MixerInput {
		public PCM pcm;
		public float volume;
		public float pitch;
		public int pos = -1;
		public float posf = 0.0f;
		public boolean loop;
		public long id;
		public long startedAtNanos;
		public int channel = -1;
	}
}
