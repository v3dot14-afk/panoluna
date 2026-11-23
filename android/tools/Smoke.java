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

        // 7. piano 24 scatti: 24 punti, tilt a 4 livelli, pan ogni 60 gradi
        PanoPlan plan = PanoPlan.shot24();
        check(plan.size() == 24, "24 кадра");
        check(plan.getRows() == 4 && plan.getColumns() == 6, "4x6");
        float[] tilts = new float[4];
        for (PanoPlan.Shot s : plan.shots()) tilts[s.row] = s.tilt;
        check(Math.abs(tilts[0] - (-52f)) < 0.001f && Math.abs(tilts[3] - 52f) < 0.001f,
                "tilt -52..+52");
        // il senso serpentino: la riga 2 riparte da 300
        PanoPlan.Shot row2first = plan.shots().get(6);
        check(Math.abs(row2first.pan - 300f) < 0.001f, "змейка строка 2 pan=300");

        // 8. PTGui: 25 linee o, dummy a posto ultimo con proiezione f2, p-line 2:1
        String pts = plan.toPtGuiTemplate("PanoLuna_24shots");
        int oLines = 0;
        String lastO = "";
        for (String line : pts.split("\r\n")) {
            if (line.startsWith("o ")) {
                oLines++;
                lastO = line;
            }
        }
        check(oLines == 25, "25 строк o (24 фото + dummy), найдено " + oLines);
        check(lastO.contains("f2"), "dummy o-line последний с f2: " + lastO);
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
