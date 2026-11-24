import com.panoluna.app.camera.PanoPlan;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Genera i template di consegna dallo stesso codice dell'app: i file in templates/ e dist/
 * sono esattamente quelli che l'app esporta sul telefono.
 * Ogni build importa un solo set: la panoramica di 24 scatti.
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

        // Solo la panoramica di 24 scatti.
        PanoPlan plan = PanoPlan.shot24();

        String job = "PanoLuna_24shots";
        write(new File(ptgui, job + ".pts"), plan.toPtGuiTemplate(job));
        write(new File(ptgui, job + ".papywizard.xml"), plan.toPapywizardXml());
        write(new File(pano2vr, job + ".p2vr"),
                plan.toPano2vrProject(job + "_equirectangular.jpg"));

        // Il lavoro completo in un'unica cartella.
        File jobDir = new File(dist, job);
        jobDir.mkdirs();
        write(new File(jobDir, job + ".pts"), plan.toPtGuiTemplate(job));
        write(new File(jobDir, job + ".papywizard.xml"), plan.toPapywizardXml());
        write(new File(jobDir, job + ".p2vr"),
                plan.toPano2vrProject(job + "_equirectangular.jpg"));
        write(new File(jobDir, "README.txt"), readme(plan));

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
        sb.append("1) PTGui: откройте проект PanoLuna_24shots.pts\n");
        sb.append("   и перетащите ").append(plan.size())
                .append(" фотографии: углы pan/pitch уже заданы по индексу.\n");
        sb.append("   Выход: equirectangular 27654x13827 (2:1).\n\n");
        sb.append("2) Pano2VR: два пути.\n");
        sb.append("   a) проект PanoLuna_24shots.p2vr, который указывает на equirectangular\n");
        sb.append("      сшитую в PTGui (переименуйте в PanoLuna_24shots_equirectangular.jpg).\n");
        sb.append("   b) Pro > File > Import Papywizard, с PanoLuna_24shots.papywizard.xml.\n");
        return sb.toString();
    }

    private static void write(File file, String content) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        out.write(content.getBytes("UTF-8"));
        out.close();
        System.out.println("  " + file);
    }
}
