package bms.player.beatoraja.audio;

import java.nio.ByteBuffer;
import java.nio.file.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bms.player.beatoraja.Config;
import com.portaudio.*;

/**
 * PortAudioドライバ
 * 
 * @author exch
 */
public class PortAudioDriver extends AbstractAudioDriver<PCM> implements Runnable {
	private static final Logger logger = LoggerFactory.getLogger(PortAudioDriver.class);
	private static final int WRITE_RETRY_COUNT = 1;

	private static DeviceInfo[] devices;
	
	private BlockingStream stream;
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
					input.pos = 0;
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
		int failures = 0;
		while (!stop) {
			try {
				stream.write(buffer, framesPerBuffer);
				return;
			} catch (RuntimeException e) {
				logger.warn("PortAudio stream write failed", e);
			}

			if (shouldReopenStream(++failures)) {
				reopenStream();
				// The failed write may be partial, so resume with the next buffer instead of replaying it.
				return;
			}
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

	private void reopenStream() {
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
		if (!stop) {
			stream = openStream();
		}
	}

	public void dispose() {
		super.dispose();
		if(stream != null) {
			stop = true;
			long l = System.currentTimeMillis();
			while(mixer.isAlive() && System.currentTimeMillis() - l < 1000);
			stream.stop();
			stream.close();
			
			stream = null;

			PortAudio.terminate();
//			System.out.println( "JPortAudio test complete." );			
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
		public int channel = -1;
	}
}
