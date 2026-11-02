package bms.player.beatoraja.audio;

import bms.player.beatoraja.Config;
import bms.player.beatoraja.AudioConfig;
import com.portaudio.BlockingStream;
import com.portaudio.DeviceInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class PortAudioDriverTest {
    @Test
    void recoversAfterWriteTimeoutsAndFailedRestart() throws Exception {
        FakeStream original = new FakeStream();
        original.writeFailure = new RuntimeException("Wait timed out");
        FakeStream failedRestart = new FakeStream();
        failedRestart.startFailure = new RuntimeException("Device not ready");
        FakeStream recovered = new FakeStream();
        var streams = new ArrayDeque<>(List.of(original, failedRestart, recovered));
        try (TestDriver driver = new TestDriver(() -> {
            if (streams.peek() == failedRestart) {
                assertEquals(1, original.closes);
            } else if (streams.peek() == recovered) {
                assertEquals(1, failedRestart.closes);
            }
            return streams.remove();
        })) {
            invoke(driver, "writeBuffer");
            assertEquals(2, original.writes);
            assertEquals(1, original.aborts);
            assertEquals(1, original.closes);
            assertEquals(1, failedRestart.starts);
            assertEquals(1, failedRestart.closes);
            assertEquals(1, recovered.starts);
            assertEquals(0, recovered.writes); // The failed buffer must not be replayed.

            invoke(driver, "writeBuffer");
            assertEquals(1, recovered.writes);
            assertTrue(streams.isEmpty());
        }
        assertEquals(1, recovered.closes);
    }

    @Test
    void preservesStartFailureWhenCleanupAlsoFails() {
        FakeStream stream = new FakeStream();
        stream.startFailure = new RuntimeException("Start failed");
        stream.closeFailure = new RuntimeException("Close failed");

        RuntimeException failure = assertThrows(RuntimeException.class, () -> new TestDriver(() -> stream));

        assertSame(stream.startFailure, failure);
        assertArrayEquals(new Throwable[]{stream.closeFailure}, failure.getSuppressed());
        assertEquals(1, stream.closes);
    }

    @Test
    void retriesOneFailedWriteWithoutReopening() throws Exception {
        FakeStream stream = new FakeStream() {
            @Override
            public boolean write(float[] buffer, int frames) {
                if (++writes == 1) {
                    throw new RuntimeException("Wait timed out");
                }
                return false;
            }
        };
        try (TestDriver driver = new TestDriver(() -> stream)) {
            invoke(driver, "writeBuffer");
            assertEquals(2, stream.writes);
            assertEquals(1, stream.starts);
            assertEquals(0, stream.closes);
        }
    }

    @Test
    void catchesUpNewInputsOnlySinceTheyStarted() throws Exception {
        try (TestDriver driver = new TestDriver(FakeStream::new)) {
            Field inputsField = PortAudioDriver.class.getDeclaredField("inputs");
            inputsField.setAccessible(true);
            var inputs = (PortAudioDriver.MixerInput[]) inputsField.get(driver);
            var input = inputs[0];
            input.pcm = new FloatPCM(2, 1000, 0, 20_000, new float[20_000]);
            input.pitch = 1;
            input.pos = 0;
            long now = System.nanoTime();
            input.startedAtNanos = now - 100_000_000L;

            Method catchUp = PortAudioDriver.class.getDeclaredMethod("catchUpInputs", long.class);
            catchUp.setAccessible(true);
            synchronized (inputs) {
                catchUp.invoke(driver, now - 10_000_000_000L);
                long elapsed = System.nanoTime() - input.startedAtNanos;
                assertTrue(input.pos >= 200);
                assertTrue(input.pos <= (long) (elapsed * 1000.0 / 1_000_000_000) * 2);
            }
        }
    }

    @Test
    void catchUpWrapsLoopsAndPreservesFractionalFrames() {
        var input = new PortAudioDriver.MixerInput();
        input.pcm = new FloatPCM(2, 1000, 0, 2000, new float[2000]);
        input.pos = 1800;
        input.posf = 0.25f;
        input.pitch = 0.5f;
        input.loop = true;

        PortAudioDriver.advanceInput(input, 2_500_000_000L, 1000, 0.5f, 2);

        assertEquals(1050, input.pos);
        assertEquals(0.25f, input.posf);
    }

    @Test
    void catchUpStopsFinishedOneShotsAndIgnoresNonpositiveElapsedTime() {
        var input = new PortAudioDriver.MixerInput();
        input.pcm = new FloatPCM(2, 1000, 0, 2000, new float[2000]);
        input.pos = 1800;
        input.posf = 0.5f;
        input.pitch = 1;

        PortAudioDriver.advanceInput(input, 0, 1000, 1, 2);
        PortAudioDriver.advanceInput(input, -1, 1000, 1, 2);
        assertEquals(1800, input.pos);
        assertEquals(0.5f, input.posf);

        PortAudioDriver.advanceInput(input, 100_000_000L, 1000, 1, 2);
        assertEquals(-1, input.pos);
    }

    private static void invoke(PortAudioDriver driver, String name) throws Exception {
        Method method = PortAudioDriver.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(driver);
    }

    private static Config config() {
        Config config = new Config();
        config.setAudioConfig(new AudioConfig());
        config.getAudioConfig().setSampleRate(1000);
        config.getAudioConfig().setDeviceBufferSize(10);
        config.getAudioConfig().setDeviceSimultaneousSources(1);
        return config;
    }

    private static DeviceInfo device() {
        DeviceInfo device = new DeviceInfo();
        device.name = "Test device";
        return device;
    }

    private static class TestDriver extends PortAudioDriver implements AutoCloseable {
        TestDriver(Supplier<BlockingStream> opener) {
            super(config(), new DeviceInfo[]{device()}, opener);
        }

        @Override
        public void run() {
            // Run mixer operations explicitly so tests never initialize native audio or COM.
        }

        @Override
        public void close() throws Exception {
            dispose();
            invoke(this, "closeStream");
        }
    }

    private static class FakeStream extends BlockingStream {
        int starts, writes, aborts, closes;
        RuntimeException startFailure, writeFailure, closeFailure;

        @Override
        public void start() {
            starts++;
            if (startFailure != null) throw startFailure;
        }

        @Override
        public boolean write(float[] buffer, int frames) {
            writes++;
            if (writeFailure != null) throw writeFailure;
            return false;
        }

        @Override
        public void abort() {
            aborts++;
        }

        @Override
        public void close() {
            closes++;
            if (closeFailure != null) throw closeFailure;
        }
    }
}
