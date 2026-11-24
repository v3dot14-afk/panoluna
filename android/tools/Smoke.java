import com.panoluna.app.proto.*;
import com.panoluna.app.camera.PanoPlan;
import java.util.List;

public final class Smoke {
    static int failures = 0;

    static void check(boolean ok, String what) {
        if (!ok) { failures++; System.out.println("FAIL " + what); }
        else System.out.println("ok   " + what);
    }

    public static void main(String[] args) throws Exception {
        // 1. round-trip di un frame di comando
        byte[] body = Messages.takePicture(0);
        byte[] frame = Ucd2.command(7, ProtoCodes.TAKE_PICTURE, 4242, body);
        Ucd2.FrameAssembler asm = new Ucd2.FrameAssembler();
        asm.append(frame, frame.length);
        List<Ucd2.Frame> frames = asm.drain();
        check(frames.size() == 1, "один фрейм");
        Ucd2.Frame f = frames.get(0);
        check(f.type == Ucd2.TYPE_FILE, "тип команды");
        check(f.code == ProtoCodes.TAKE_PICTURE, "код takePicture");
        check(f.requestId == 4242, "requestId");
        check(f.direction == Ucd2.DIRECTION_REQUEST, "направление запроса");
        check(f.payload.length == body.length, "длина payload");

        // 2. due frame in un unico append, piu' byte spuri davanti
        byte[] second = Ucd2.command(8, ProtoCodes.GET_OPTIONS, 4243, Messages.getOptions(11, 20));
        byte[] merged = new byte[3 + frame.length + second.length];
        merged[0] = 'x'; merged[1] = 'y'; merged[2] = 'z';
        System.arraycopy(frame, 0, merged, 3, frame.length);
        System.arraycopy(second, 0, merged, 3 + frame.length, second.length);
        Ucd2.FrameAssembler asm2 = new Ucd2.FrameAssembler();
        asm2.append(merged, merged.length);
        List<Ucd2.Frame> two = asm2.drain();
        check(two.size() == 2, "два фрейма с лишними преамбулами");
        check(two.get(1).code == ProtoCodes.GET_OPTIONS, "код второго фрейма");

        // 3. frame spezzati su due append
        Ucd2.FrameAssembler asm3 = new Ucd2.FrameAssembler();
        asm3.append(frame, 10);
        asm3.append(java.util.Arrays.copyOfRange(frame, 10, frame.length), frame.length - 10);
        check(asm3.drain().size() == 1, "фрейм, собранный из двух частей");

        // 4. hello
        byte[] hello = Ucd2.hello(3);
        check(hello.length == 16, "hello 16 байт");
        check(hello[6] == Ucd2.TYPE_STREAM, "hello тип stream");
        check((hello[8] & 0xFF) == 0 && (hello[11] & 0xFF) == 0, "hello нулевая длина");
        check(hello[12] == (byte) 0xf6 && hello[15] == 0x09, "hello токен");

        // 5. gimbal: campo 1 = 1 (MOVE), annidato 12 con zigzag
        byte[] move = Messages.gimbalMove(-40, 60);
        Proto.Reader r = new Proto.Reader(move);
        check(r.intOrNull(1) != null && r.intOrNull(1) == 1, "гимбал действие MOVE");
        Proto.Field nestedField = r.find(2);
        check(nestedField != null && nestedField.isLength(), "гимбал вложенный payload");
        byte[] nested = nestedField.length;
        Proto.Reader n = new Proto.Reader(nested);
        Proto.Field fx = n.find(1);
        Proto.Field fy = n.find(2);
        int vx = fx == null ? 0 : fx.asSInt();
        int vy = fy == null ? 0 : fy.asSInt();
        check(vx == 60, "гимбал ось1 (вертикальная) 60, получено " + vx);
        check(vy == -40, "гимбал ось2 (горизонтальная) -40, получено " + vy);

        // 6. gimbal stop
        byte[] stop = Messages.gimbalMove(0, 0);
        check(stop.length == 4 && (stop[0] & 0xFF) == 0x08 && stop[1] == 1
                && (stop[2] & 0xFF) == 0x12 && stop[3] == 0, "stop = 08 01 12 00");

        // 6b. объявление live-view: SET_ACCESS_CAMERA_FILE_STATE(LIVE_VIEW=5) = 08 05
        // разворачивает камеру до START_LIVE_STREAM, без него гимбал заперт (Ripwords)
        byte[] announce = Messages.setAccessCameraFileState(5);
        check(announce.length == 2 && (announce[0] & 0xFF) == 0x08 && announce[1] == 5,
                "live-view объявление = 08 05");
        check(ProtoCodes.SET_ACCESS_CAMERA_FILE_STATE == 118,
                "код SET_ACCESS_CAMERA_FILE_STATE = 118");

        // 7. План 24 кадров: 24 точки, 4 уровня tilt, pan внутри ХОДА гимбала
        // (-57..+235), а не по кругу 0..360: за 235° гимбал физически не ходит,
        // и старый план упирался в предел на 4-м кадре.
        PanoPlan plan = PanoPlan.shot24();
        check(plan.size() == 24, "24 кадра");
        check(plan.getRows() == 4 && plan.getColumns() == 6, "4x6");
        check(Math.abs(plan.getPanStep() - 58.4f) < 0.01f,
                "pan step соответствует полному физическому ходу 58.4°");
        check(plan.getOverlapPercent() == 25,
                "перекрытие рассчитано по фактическому шагу и FOV (25%)");
        float[] tilts = new float[4];
        for (PanoPlan.Shot s : plan.shots()) tilts[s.row] = s.tilt;
        // tilt от -58.9 до +58.9: каждый ряд покрывает +-31.15, значит нижний ряд
        // доходит до -90 (надир), верхний до +90 (зенит) — сфера закрыта целиком.
        check(Math.abs(tilts[0] - (-58.9f)) < 0.1f && Math.abs(tilts[3] - 58.9f) < 0.1f,
                "tilt -58.9..+58.9 (зенит и надир покрыты)");
        check(Math.abs(tilts[0] - 31.15f + 90f) < 0.2f,
                "нижний ряд доходит до надира");
        // Змейка: строка 2 идёт в обратном порядке, от верхнего предела хода.
        PanoPlan.Shot row2first = plan.shots().get(6);
        check(Math.abs(row2first.pan - PanoPlan.PAN_MAX_DEG) < 0.001f,
                "змейка строка 2 pan=" + PanoPlan.PAN_MAX_DEG);
        // Все точки плана обязаны лежать внутри физического хода гимбала.
        boolean allInside = true;
        for (PanoPlan.Shot s2 : plan.shots()) {
            if (s2.pan < PanoPlan.PAN_MIN_DEG - 0.001f || s2.pan > PanoPlan.PAN_MAX_DEG + 0.001f) {
                allInside = false;
            }
        }
        check(allInside, "все pan внутри хода гимбала");
        // Охват сектора с учётом FOV должен покрывать полный круг 360°.
        check((PanoPlan.PAN_MAX_DEG - PanoPlan.PAN_MIN_DEG) + 77.7f >= 360f,
                "охват хода + FOV покрывает 360°");

        // 8. PTGui: ровно 24 строки o (по одной на кадр), без dummy, p-line 2:1,
        // контрольные точки между соседями, yaw в пределах 0..360.
        String pts = plan.toPtGuiTemplate("PanoLuna_24shots");
        int oLines = 0;
        int cpLines = 0;
        boolean yawInRange = true;
        for (String line : pts.split("\r\n")) {
            if (line.startsWith("o ")) {
                oLines++;
                int idx = line.indexOf(" y");
                if (idx > 0) {
                    int end = line.indexOf(' ', idx + 2);
                    try {
                        float yaw = Float.parseFloat(line.substring(idx + 2, end));
                        if (yaw < -0.01f || yaw > 360.01f) {
                            yawInRange = false;
                        }
                    } catch (Exception ignored) {
                    }
                }
            } else if (line.startsWith("c ")) {
                cpLines++;
            }
        }
        check(oLines == 24, "24 строки o (по одной на кадр), найдено " + oLines);
        check(!pts.contains("dummy"), "без dummy-изображения");
        check(cpLines > 0, "контрольные точки присутствуют: " + cpLines);
        check(yawInRange, "yaw в пределах 0..360");
        check(pts.contains("p w27654 h13827 f2 v360"), "p-line 2:1");
        check(pts.contains("#-fileversion 49"), "fileversion 49");
        check(pts.contains("v77.7"), "hfov измеренный 77.7");

        // 9. Papywizard: 24 pict
        String papy = plan.toPapywizardXml();
        int picts = 0;
        for (String line : papy.split("\n")) if (line.contains("<pict ")) picts++;
        check(picts == 24, "24 pict");

        // 10. p2vr: xml valido almeno nei tag
        String p2vr = plan.toPano2vrProject("x.jpg");
        check(p2vr.startsWith("<?xml"), "p2vr xml");
        check(p2vr.contains("<pano2vrconfig>"), "p2vr root");
        check(p2vr.contains("<filename>x.jpg</filename>"), "p2vr input");

        System.out.println(failures == 0 ? "ВСЁ OK" : failures + " ОШИБОК");
        if (failures != 0) System.exit(1);
    }

    static int zigzagDecode(int value) {
        return (value & 1) == 0 ? (value >>> 1) : -((value >>> 1) + 1);
    }
}
