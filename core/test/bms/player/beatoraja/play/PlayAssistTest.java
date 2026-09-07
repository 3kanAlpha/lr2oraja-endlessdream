package bms.player.beatoraja.play;

import bms.model.*;
import bms.player.beatoraja.*;
import bms.player.beatoraja.audio.AudioDriver;
import bms.player.beatoraja.audio.BMSLoudnessAnalyzer;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.modmenu.ImGuiRenderer;
import bms.player.beatoraja.ir.*;
import bms.player.beatoraja.result.*;
import bms.player.beatoraja.skin.SkinHeader;
import bms.player.beatoraja.song.SongData;
import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.GdxNativesLoader;
import imgui.type.ImBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static bms.player.beatoraja.skin.SkinProperty.TIMER_PLAY;
import static org.junit.jupiter.api.Assertions.*;

class PlayAssistTest {
    @TempDir Path temp;
    private static Application previousApp;
    private static Graphics previousGraphics;
    private static GL20 previousGL, previousGL20;
    private static String previousControllerManager;
    private static ImBoolean option;

    @BeforeAll
    static void headlessGraphics() throws Exception {
        previousApp = Gdx.app;
        previousGraphics = Gdx.graphics;
        previousGL = Gdx.gl;
        previousGL20 = Gdx.gl20;
        previousControllerManager = Controllers.preferredManager;
        GdxNativesLoader.load();
        Gdx.app = proxy(Application.class, (method, args) -> method.equals("getType")
                ? Application.ApplicationType.HeadlessDesktop : null);
        Gdx.graphics = proxy(Graphics.class, (method, args) -> null);
        Gdx.gl = Gdx.gl20 = proxy(GL20.class, (method, args) -> null);
        Controllers.preferredManager = null;
        Controllers.getControllers(); // Install the built-in headless controller manager.
        option = (ImBoolean) field(ImGuiRenderer.class, "PLAY_ASSIST").get(null);
        assertFalse(option.get());
    }

    @AfterEach
    void resetOption() {
        option.set(false);
    }

    @AfterAll
    static void restoreGraphics() {
        Gdx.app = previousApp;
        Gdx.graphics = previousGraphics;
        Gdx.gl = previousGL;
        Gdx.gl20 = previousGL20;
        Controllers.preferredManager = previousControllerManager;
    }

    @Test
    void schedulesOnlyPerformanceNotesOnceAndUsesSeparateVolumes() throws Exception {
        var h = harness(true, chart());
        var processor = new KeySoundProcessor(h.player);
        var thread = processor.new AutoplayThread(h.model, 0);
        thread.start();
        try {
            h.clock.time = 999_999;
            Thread.sleep(20);
            assertTrue(h.events.isEmpty());
            h.clock.time = 1_000_000;
            awaitNotes(h, 6); // BGM, key, scratch, LN, CN and HCN starts
            assertEquals(List.of(10, 1, 3, 5, 7, 2), noteIds(h));
            assertEquals(.2f, h.events.get(0).volume);
            assertEquals(.7f, h.events.get(1).volume);
            Thread.sleep(20);
            assertEquals(6, noteIds(h).size());
            h.clock.time = 2_000_000;
            thread.join(3000);
            assertFalse(thread.isAlive());
            assertEquals(List.of(10, 1, 3, 5, 7, 2, 4, 6, 8), noteIds(h));
        } finally {
            // The thread is created directly so its lifecycle can be checked without a game loop.
            h.clock.time = 3_000_000;
            thread.join(3000);
        }
    }

    @Test
    void handlesNoBgmStartOffsetAndAlreadyJudgedNotes() throws Exception {
        var model = chart();
        model.getAllTimeLines()[1].getNote(0).setState(5);
        var h = harness(true, model);
        h.clock.time = 2_000_000;
        var processor = new KeySoundProcessor(h.player);
        var thread = processor.new AutoplayThread(model, 2_000_000);
        thread.run();
        assertEquals(List.of(4, 6, 8), noteIds(h));

        h.events.clear();
        var lone = new TimeLine(0, 0, 8);
        var note = new NormalNote(20);
        note.setState(5);
        lone.setNote(0, note);
        model.setAllTimeLine(new TimeLine[]{lone});
        processor.new AutoplayThread(model, 0).run();
        assertEquals(List.of(20), noteIds(h));
    }

    @Test
    void offPlaysOnlyBgmAndOptionIsCapturedPerPlay() throws Exception {
        var h = harness(false, chart());
        option.set(true);
        assertFalse(h.player.isPlayAssistEnabled());
        h.clock.time = 2_000_000;
        new KeySoundProcessor(h.player).new AutoplayThread(h.model, 0).run();
        assertEquals(List.of(10), noteIds(h));
        var enabled = harness(true, chart());
        option.set(false);
        assertTrue(enabled.player.isPlayAssistEnabled());
    }

    @Test
    void adjustedVolumeAppliesToBgmAndKeys() throws Exception {
        var h = harness(true, chart());
        field(BMSPlayer.class, "adjustedVolume").setFloat(h.player, .4f);
        h.clock.time = 2_000_000;
        new KeySoundProcessor(h.player).new AutoplayThread(h.model, 0).run();
        assertEquals(9, h.events.size());
        assertTrue(h.events.stream().allMatch(e -> e.volume == .4f));
    }

    @Test
    void playAssistTimingOffsetMovesBgmAndNotesTogether() throws Exception {
        var h = harness(true, chart());
        h.player.resource.getPlayerConfig().setJudgetiming(100);
        h.model.setAllTimeLine(new TimeLine[]{h.model.getAllTimeLines()[1]});
        h.clock.time = 900_000;

        new KeySoundProcessor(h.player).new AutoplayThread(h.model, 0).run();

        assertEquals(List.of(10, 1, 3, 5, 7, 2), noteIds(h));
    }

    @Test
    void negativePlayAssistTimingOffsetDelaysAudio() throws Exception {
        var h = harness(true, chart());
        h.player.resource.getPlayerConfig().setJudgetiming(-100);
        h.model.setAllTimeLine(new TimeLine[]{h.model.getAllTimeLines()[1]});
        h.clock.time = 1_099_999;
        var processor = new KeySoundProcessor(h.player);
        var thread = processor.new AutoplayThread(h.model, 0);

        thread.start();
        Thread.sleep(20);
        assertTrue(h.events.isEmpty());

        h.clock.time = 1_100_000;
        thread.join(3000);
        assertFalse(thread.isAlive());
        assertEquals(List.of(10, 1, 3, 5, 7, 2), noteIds(h));
    }

    @Test
    void inputAndLongNoteFailuresChangeJudgmentsButNeverAssistedAudio() throws Exception {
        List<String> normal = playInputs(false);
        List<String> assisted = playInputs(true);
        assertEquals(normal, assisted, "judgments, combo, gauge and save flags must match");
    }

    @Test
    void stoppingPlaybackDoesNotEmitFutureNotes() throws Exception {
        var h = harness(true, chart());
        var processor = new KeySoundProcessor(h.player);
        processor.startBGPlay(h.model, 0);
        var thread = (Thread) field(KeySoundProcessor.class, "autoThread").get(processor);
        processor.stopBGPlay();
        h.clock.time = 3_000_000;
        thread.join(3000);
        assertFalse(thread.isAlive());
        assertTrue(h.events.isEmpty());
    }

    @Test
    void singleAndCourseClearsAreSavedAndSubmittedWithAssistOnOrOff() throws Exception {
        for (boolean assist : new boolean[]{false, true}) {
            for (boolean course : new boolean[]{false, true}) {
                var model = chart();
                var tl = new TimeLine(1, 1_000_000, 8);
                tl.setNote(0, new NormalNote(1));
                model.setAllTimeLine(new TimeLine[]{tl});
                var h = harness(assist, model);
                var resource = h.player.resource;
                h.input.change(1_000_000, 0, true);
                h.clock.time = 1_000_000;
                h.judge.update(1_000_000);
                var score = h.player.createScoreData();
                assertEquals(ClearType.Max.id, score.getClear());
                resource.setScoreData(score);

                AbstractResult result;
                var accessor = h.player.main.getPlayDataAccessor();
                ScoreData saved;
                if (course) {
                    field(PlayerResource.class, "course").set(resource, new BMSModel[]{model});
                    var data = new CourseData();
                    data.setName("Test course");
                    data.setSong(new BMSModel[]{model});
                    resource.setCourseData(data);
                    resource.setCourseScoreData(score);
                    resource.setMaxcombo(h.judge.getCombo());
                    var cr = new CourseResult(h.player.main);
                    cr.updateScoreDatabase();
                    saved = accessor.readScoreData(new BMSModel[]{model}, 0, 0, resource.getConstraint());
                    result = cr;
                } else {
                    var mr = new MusicResult(h.player.main);
                    var save = MusicResult.class.getDeclaredMethod("updateScoreDatabase");
                    save.setAccessible(true);
                    save.invoke(mr);
                    saved = accessor.readScoreData(model, 0);
                    result = mr;
                }
                assertNotNull(saved);
                assertEquals(ClearType.Max.id, saved.getClear());
                assertEquals(2, saved.getExscore());

                var sent = new CopyOnWriteArrayList<IRScoreData>();
                IRConnection ir = proxy(IRConnection.class, (method, args) -> {
                    boolean send = method.equals(course ? "sendCoursePlayData" : "sendPlayData");
                    if (send) sent.add((IRScoreData) args[1]);
                    return new IRResponse<Object>() {
                        public boolean isSucceeded() { return send; }
                        public String getMessage() { return "Recorded locally; no network"; }
                        public Object getData() { return null; }
                    };
                });
                field(MainController.class, "ir").set(h.player.main,
                        new MainController.IRStatus[]{new MainController.IRStatus(new IRConfig(), ir, null)});
                result.prepare();
                var state = field(AbstractResult.class, "state");
                long deadline = System.nanoTime() + 3_000_000_000L;
                while (state.getInt(result) != AbstractResult.STATE_IR_FINISHED && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertEquals(AbstractResult.STATE_IR_FINISHED, state.getInt(result));
                assertEquals(1, sent.size());
                assertEquals(ClearType.Max, sent.get(0).clear);
            }
        }
    }

    private List<String> playInputs(boolean assist) throws Exception {
        var h = harness(assist, chart());
        List<String> states = new ArrayList<>();
        // Empty hit (including invisible sound), early/late key hits, scratch, LN/HCN early release and re-press.
        long[] times = {500_000, 510_000, 990_000, 1_000_000, 1_010_000, 1_050_000,
                1_100_000, 1_400_000, 1_450_000, 1_700_000, 2_000_001, 2_500_000};
        for (long time : times) {
            if (time == 500_000) h.input.change(time, 0, true);
            if (time == 510_000) h.input.change(time, 0, false);
            if (time == 990_000) h.input.change(time, 0, true);
            if (time == 1_000_000) {
                h.input.change(time, 1, true);
                h.input.change(time, 2, true);
                h.input.change(time, 3, true); // mine damage still applies
                h.input.change(time, 4, true);
            }
            if (time == 1_010_000) h.input.change(time, 7, true);
            if (time == 1_050_000) {
                h.input.change(time, 1, false);
                h.input.change(time, 2, false);
                h.input.change(time, 4, false);
            }
            if (time == 1_450_000) h.input.change(time, 2, true);
            if (time == 1_700_000) h.input.change(time, 2, false);
            h.clock.time = time;
            h.judge.update(time);
            states.add(h.judge.getScoreData().getExscore() + ":" + h.judge.getCombo() + ":"
                    + h.player.getGauge().getValue() + ":" + Arrays.toString(h.judge.getGhost()));
        }
        assertEquals(assist, h.events.stream().noneMatch(e -> e.note != null));
        assertTrue(h.events.stream().anyMatch(e -> e.method.equals("guide")), "judge guide SE stays enabled");
        assertTrue(h.player.resource.isUpdateScore());
        assertTrue(h.player.resource.isUpdateCourseScore());
        assertFalse(h.player.resource.isForceNoIRSend());
        assertEquals(0, field(BMSPlayer.class, "assist").getInt(h.player));
        states.add(h.player.createScoreData().getClear() + ":" + h.player.resource.isUpdateScore());
        return states;
    }

    private Harness harness(boolean assist, BMSModel model) throws Exception {
        option.set(assist);
        var config = new Config();
        config.setAudioConfig(new AudioConfig());
        config.setUseSongInfo(false);
        config.setUseDiscordRPC(false);
        config.setUseObsWs(false);
        config.setEnableIpfs(false);
        config.setEnableHttp(false);
        config.getAudioConfig().setDriver(AudioConfig.DriverType.OpenAL);
        config.getAudioConfig().setBgvolume(.2f);
        config.getAudioConfig().setKeyvolume(.7f);
        config.setPlayerpath(Files.createTempDirectory(temp, "player").toString());
        config.setPlayername("test");
        Files.createDirectories(Path.of(config.getPlayerpath(), "test"));
        var playerConfig = new PlayerConfig();
        playerConfig.setIrconfig(new IRConfig[0]);
        playerConfig.setNotesDisplayTimingAutoAdjust(false);
        var events = new CopyOnWriteArrayList<AudioEvent>();
        AudioDriver audio = proxy(AudioDriver.class, (method, args) -> {
            if (method.equals("play") && args[0] instanceof Note n) {
                events.add(new AudioEvent("play", n, (float) args[1]));
            } else if (method.equals("setVolume") && args[0] instanceof Note n) {
                events.add(new AudioEvent("volume", n, (float) args[1]));
            } else if (method.equals("play") && args[0] instanceof Integer) {
                events.add(new AudioEvent("guide", null, 0));
            }
            return null;
        });
        var resource = new PlayerResource(audio, config, playerConfig, null);
        field(PlayerResource.class, "model").set(resource, model);
        resource.setOriginalMode(model.getMode());
        resource.setSongdata(new SongData(model, false));
        resource.setReplayData(new ReplayData());
        resource.setPlayMode(BMSPlayerMode.PLAY);
        var clock = new Clock();
        var input = new TestInput(config, playerConfig);
        input.dispose(); // No hardware is needed after constructing the input state.
        input.resetAllKeyState();
        var controller = new MainController(null, config, playerConfig, BMSPlayerMode.PLAY, false) {
            @Override public PlayerResource getPlayerResource() { return resource; }
            @Override public AudioDriver getAudioProcessor() { return audio; }
            @Override public TimerManager getTimer() { return clock; }
            @Override public BMSPlayerInputProcessor getInputProcessor() { return input; }
        };
        var player = new BMSPlayer(controller, resource);
        clock.setMainState(player);
        var header = new SkinHeader();
        header.setSourceResolution(Resolution.SD);
        header.setDestinationResolution(Resolution.SD);
        header.setName("test");
        player.setSkin(new PlaySkin(header));
        var lanes = new LaneProperty(model.getMode());
        field(BMSPlayer.class, "laneProperty").set(player, lanes);
        field(BMSPlayer.class, "keyinput").set(player, new KeyInputProccessor(player, lanes));
        field(BMSPlayer.class, "bga").set(player, resource.getBGAManager());
        var judge = new JudgeManager(player);
        field(BMSPlayer.class, "judge").set(player, judge);
        judge.init(model, resource);
        return new Harness(model, player, judge, input, clock, events);
    }

    private static BMSModel chart() {
        var start = new TimeLine(1, 1_000_000, 8);
        var end = new TimeLine(2, 2_000_000, 8);
        start.addBackGroundNote(new NormalNote(10));
        start.setNote(0, new NormalNote(1));
        start.setNote(7, new NormalNote(2));
        for (int lane = 1; lane <= 2; lane++) {
            var head = new LongNote(lane * 2 + 1);
            var tail = new LongNote(lane * 2 + 2);
            start.setNote(lane, head);
            end.setNote(lane, tail);
            head.setType(lane == 1 ? LongNote.TYPE_LONGNOTE : LongNote.TYPE_HELLCHARGENOTE);
            head.setPair(tail);
        }
        start.setNote(3, new MineNote(11, 10));
        var cnStart = new LongNote(7);
        var cnEnd = new LongNote(8);
        start.setNote(4, cnStart);
        end.setNote(4, cnEnd);
        cnStart.setType(LongNote.TYPE_CHARGENOTE);
        cnStart.setPair(cnEnd);
        var hidden = new TimeLine(0, 100_000, 8);
        hidden.setHiddenNote(0, new NormalNote(12));
        var model = new BMSModel();
        model.setMode(Mode.BEAT_7K);
        model.setBpm(120);
        model.setJudgerank(100);
        model.setTotal(300);
        model.setSHA256("1".repeat(64));
        model.setAllTimeLine(new TimeLine[]{hidden, start, end});
        return model;
    }

    private static List<Integer> noteIds(Harness h) {
        return h.events.stream().filter(e -> e.note != null).map(e -> e.note.getWav()).toList();
    }

    private static void awaitNotes(Harness h, int count) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (noteIds(h).size() < count && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(count, noteIds(h).size());
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            if (method.getName().equals("equals")) return self == args[0];
            Object value = handler.apply(method.getName(), args);
            if (value != null) return value;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == float.class) return 0f;
            if (method.getReturnType() == long.class) return 0L;
            return null;
        }));
    }

    private static class Clock extends TimerManager {
        volatile long time;
        @Override public long getNowMicroTime(int id) { return id == TIMER_PLAY ? time : super.getNowMicroTime(id); }
        @Override public long getNowTime() { return time / 1000; }
    }

    private static class TestInput extends BMSPlayerInputProcessor {
        TestInput(Config config, PlayerConfig player) { super(config, player); }
        void change(long time, int key, boolean pressed) { keyChanged(null, time, key, pressed); }
    }

    private record AudioEvent(String method, Note note, float volume) {}
    private record Harness(BMSModel model, BMSPlayer player, JudgeManager judge, TestInput input,
                           Clock clock, CopyOnWriteArrayList<AudioEvent> events) {}
}
