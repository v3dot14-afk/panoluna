import com.panoluna.app.camera.PanoPlan;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Genera i template di consegna dallo stesso codice dell'app: i file in templates/ e dist/
 * sono esattamente quelli che PanoActivity esporta sul telefono.
 */
public final class ExportTemplates {

    public static void main(String[] args) throws Exception {
        File templates = new File(args[0]);
        File dist = new File(args[1]);
        File ptgui = new File(templates, "ptgui");
        File pano2vr = new File(templates, "pano2vr");
        ptgui.mkdirs();
        pano2vr.mkdirs();
        dist.mkdirs();

        PanoPlan plan24 = PanoPlan.shot24();
        PanoPlan plan18 = new PanoPlan(3, 6, -52f, 52f, 30);
        PanoPlan plan12 = new PanoPlan(3, 4, -52f, 52f, 30);
        PanoPlan plan36 = new PanoPlan(6, 6, -52f, 52f, 30);

        for (PanoPlan plan : new PanoPlan[]{plan24, plan18, plan12, plan36}) {
            String job = "PanoLuna_" + plan.size() + "scatti";
            write(new File(ptgui, job + ".pts"), plan.toPtGuiTemplate(job));
            write(new File(ptgui, job + ".papywizard.xml"), plan.toPapywizardXml());
            write(new File(pano2vr, job + ".p2vr"),
                    plan.toPano2vrProject(job + "_equirectangular.jpg"));
            write(new File(dist, job + ".csv"), plan.toCsv());
        }

        // Il lavoro completo del caso base (24 scatti) in un'unica cartella.
        File jobDir = new File(dist, "PanoLuna_24scatti");
        jobDir.mkdirs();
        write(new File(jobDir, "PanoLuna_24scatti.pts"), plan24.toPtGuiTemplate("PanoLuna_24scatti"));
        write(new File(jobDir, "PanoLuna_24scatti.papywizard.xml"), plan24.toPapywizardXml());
        write(new File(jobDir, "PanoLuna_24scatti.p2vr"),
                plan24.toPano2vrProject("PanoLuna_24scatti_equirectangular.jpg"));
        write(new File(jobDir, "PanoLuna_24scatti.csv"), plan24.toCsv());
        write(new File(jobDir, "README.txt"), readme(plan24));

        System.out.println("template scritti in " + templates + " e " + dist);
    }

    private static String readme(PanoPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("PanoLuna - работа из ").append(plan.size()).append(" снимков\n");
        sb.append("Камера: Insta360 Luna Ultra, объектив 1x\n");
        sb.append("Сетка: ").append(plan.getRows()).append(" строк x ")
                .append(plan.getColumns()).append(" столбцов\n");
        sb.append("Перекрытие: ").append(plan.getOverlapPercent()).append("%\n");
        sb.append("Tilt: ").append(plan.getTiltBottom()).append(" .. ")
                .append(plan.getTiltTop()).append(" градусов\n\n");
        sb.append("1) PTGui: откройте проект ").append("PanoLuna_").append(plan.size()).append("scatti.pts\n");
        sb.append("   и перетащите ").append(plan.size())
                .append(" фотографии: углы pan/pitch уже заданы по индексу.\n");
        sb.append("   Выход: equirectangular 27654x13827 (2:1).\n\n");
        sb.append("2) Pano2VR: два пути.\n");
        sb.append("   a) проект ").append("PanoLuna_").append(plan.size()).append("scatti.p2vr, который указывает на equirectangular\n");
        sb.append("      сшитую в PTGui (переименуйте в ").append("PanoLuna_").append(plan.size())
                .append("scatti_equirectangular.jpg).\n");
        sb.append("   b) Pro > File > Import Papywizard, с PanoLuna_").append(plan.size())
                .append("scatti.papywizard.xml.\n\n");
        sb.append("3) ").append("PanoLuna_").append(plan.size()).append("scatti.csv - таблица углов по строкам.\n");
        return sb.toString();
    }

    private static void write(File file, String content) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        out.write(content.getBytes("UTF-8"));
        out.close();
        System.out.println("  " + file);
    }
}
