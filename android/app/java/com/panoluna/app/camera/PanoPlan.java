package com.panoluna.app.camera;

import java.util.ArrayList;
import java.util.List;

/**
 * Il piano degli scatti di una panoramica sferica.
 *
 * Il predeterminato e' la griglia di Panoshooter: 4 file x 6 colonne = 24 scatti, con la
 * lente 1x della Luna Ultra (campo orizzontale misurato 77,7 deg, verticale ~62,3 deg in 4:3).
 *
 * Le file sono ordinate dal basso verso l'alto e il senso di marcia si alterna (serpentino),
 * cosi' il gimbal non riprende mai da capo: lo spostamento fra uno scatto e il successivo e'
 * sempre un passo, mai il giro intero.
 *
 * Gli angoli sono relativi: lo scatto 0 parte da dove guarda la camera, e il modello
 * sottrae quell'offset quando esporta il template PTGui (PTGui allinea comunque da solo).
 */
public final class PanoPlan {

    /** Un punto del piano. Gli angoli sono gradi, pan e tilt. */
    public static final class Shot {
        public final int index;
        public final int row;
        public final int column;
        public final float pan;
        public final float tilt;
        public String uri;

        Shot(int index, int row, int column, float pan, float tilt) {
            this.index = index;
            this.row = row;
            this.column = column;
            this.pan = pan;
            this.tilt = tilt;
        }

        public String label() {
            return "R" + (row + 1) + " C" + (column + 1);
        }
    }

    private final List<Shot> shots = new ArrayList<Shot>();
    private final int rows;
    private final int columns;
    private final float tiltTop;
    private final float tiltBottom;
    private final int overlapPercent;

    public PanoPlan(int rows, int columns, float tiltBottom, float tiltTop, int overlapPercent) {
        this.rows = Math.max(1, Math.min(12, rows));
        this.columns = Math.max(1, Math.min(24, columns));
        this.tiltBottom = tiltBottom;
        this.tiltTop = tiltTop;
        this.overlapPercent = Math.max(10, Math.min(60, overlapPercent));
        build();
    }

    /** Il piano a 24 scatti: 4 file x 6 colonne, tilt da -52 a +52 gradi. */
    public static PanoPlan shot24() {
        return new PanoPlan(4, 6, -52f, 52f, 30);
    }

    private void build() {
        shots.clear();
        int index = 0;
        for (int row = 0; row < rows; row++) {
            float tilt = position(tiltBottom, tiltTop, rows, row);
            boolean forward = (row % 2 == 0);
            for (int step = 0; step < columns; step++) {
                int column = forward ? step : (columns - 1 - step);
                float pan = 360f * column / columns;
                shots.add(new Shot(index++, row, column, pan, tilt));
            }
        }
    }

    private static float position(float start, float end, int count, int index) {
        if (count <= 1) {
            return (start + end) / 2f;
        }
        return start + (end - start) * index / (count - 1);
    }

    public List<Shot> shots() {
        return shots;
    }

    public int size() {
        return shots.size();
    }

    public int getRows() {
        return rows;
    }

    public int getColumns() {
        return columns;
    }

    public int getOverlapPercent() {
        return overlapPercent;
    }

    public float getTiltTop() {
        return tiltTop;
    }

    public float getTiltBottom() {
        return tiltBottom;
    }

    /** Passo orizzontale fra due colonne. */
    public float getPanStep() {
        return columns == 1 ? 0f : 360f / columns;
    }

    /**
     * Campo orizzontale della lente a 1x in 4:3, misurato sulla Luna Ultra contro la gravita':
     * 77,7 gradi, non gli 81,7 dichiarati di catalogo.
     */
    public static final float MEASURED_HFOV_DEG = 77.7f;

    /** Campo verticale corrispondente, in 4:3. */
    public static final float MEASURED_VFOV_DEG = 62.3f;

    /** Focale equivalente 35 mm che produce il campo misurato, in 4:3 full frame. */
    public static final float EQUIVALENT_FOCAL_MM = 21.5f;

    /** CSV con gli angoli: e' la tabella che PTGui Papywizard e PTGui stesso possono leggere. */
    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("index;row;column;pan_deg;pitch_deg;uri\n");
        for (Shot s : shots) {
            sb.append(s.index).append(';')
                    .append(s.row + 1).append(';')
                    .append(s.column + 1).append(';')
                    .append(fmt(s.pan)).append(';')
                    .append(fmt(s.tilt)).append(';')
                    .append(s.uri == null ? "" : s.uri)
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * Template PTGui: i 24 punti con yaw/pitch, la lente rettilinea e il campo misurato.
     * Le immagini non sono incluse: PTGui applica i parametri per indice alle foto caricate.
     */
    public String toPtGuiTemplate(String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ptGui project file\r\n\r\n");
        sb.append("#-encoding utf8\r\n");
        sb.append("#-fileversion 49\r\n");
        sb.append("#-pathseparator \\\r\n");
        sb.append("# PanoLuna - Insta360 Luna Ultra, 24 scatti, lente 1x 4:3\r\n");
        sb.append("# Campo orizzontale misurato: ").append(MEASURED_HFOV_DEG).append(" gradi\r\n");
        sb.append("#-vfov 180\r\n");
        sb.append("#-resolution 300\r\n");
        sb.append("#-fixaspect 1\r\n");
        sb.append("#-outputfile \r\n");
        sb.append("#-ccdcrop 1\r\n");
        sb.append("#-hasbeenoptimized 0\r\n");
        sb.append("#-hvcpmode 1\r\n");
        sb.append("#-jpegparameters 95 0\r\n");
        sb.append("#-tiffparameters 8bit packbits alpha_assoc\r\n");
        sb.append("#-psdparameters 8bit packbits layered\r\n");
        sb.append("#-honorexiforientation 0\r\n");
        sb.append("#-cameracurve 0 0 0 0 0\r\n");
        sb.append("#-vignettingparams 0 0 0 0 0\r\n");
        sb.append("#-wbexposure 0 0 0\r\n");
        sb.append("#-pmoptexposuremode auto\r\n");
        sb.append("#-pmoptvignettingmode disabled\r\n");
        sb.append("#-pmoptwbmode disabled\r\n");
        sb.append("#-pmoptflaremode disabled\r\n");
        sb.append("#-pmoptcameracurvemode disabled\r\n");
        sb.append("#-exposurecorrection 0\r\n");
        sb.append("#-tonemapldrpanorama 1\r\n");
        sb.append("#-blendweight 100 100\r\n");
        sb.append("#-optviewpoint 00\r\n");
        sb.append("#-useexif1\r\n");
        sb.append("#-batchbuilder_useexif 0\r\n");
        sb.append("#-stitcher ptgui\r\n");
        sb.append("#-blender ptgui\r\n");
        sb.append("#-blenderfeather -0.6\r\n");
        sb.append("#-optimizer ptgui\r\n");
        sb.append("#-interpolator default\r\n");
        sb.append("#-templateapplied\r\n");
        sb.append("#-imgrotate44\r\n");
        sb.append("#-imginactive\r\n");
        sb.append("#-linktoprevious\r\n");
        sb.append("#-previewinactive\r\n");
        sb.append("#-outputcrop 0 1 0 1\r\n");
        sb.append("#-location default\r\n");
        sb.append("#-viewingdirection default\r\n");
        sb.append("#-alignsettings_generatecp 0\r\n");
        sb.append("#-alignsettings_optimize 1\r\n");
        sb.append("#-alignsettings_optimizeprealign 1\r\n");
        sb.append("#-alignsettings_straighten 1\r\n");
        sb.append("#-alignsettings_fit 1\r\n");
        sb.append("#-alignsettings_chooseprojection 0\r\n");
        sb.append("#-alignsettings_setoptimumsize 1\r\n");
        sb.append("#-alignsettings_limitsize 500\r\n");
        sb.append("#-alignsettings_optimizeexposure 0\r\n");
        sb.append("#-hdrsettings_defaultlinkmode nolink\r\n");
        sb.append("#-hdrsettings_donotask 0\r\n");
        sb.append("#-batchsettings_align 0\r\n");
        sb.append("#-batchsettings_stitch 1\r\n");
        sb.append("#-batchsettings_stitchonlyifcontrolpoints 1\r\n");
        sb.append("#-defaultprojectfilenamemode firstsourceimage\r\n");
        sb.append("#-defaultprojectfilename_custom \"_stitched\"\r\n");
        sb.append("#-defaultprojectfoldermode sourcefolder\r\n");
        sb.append("#-defaultprojectfolder_custom \"\"\r\n");
        sb.append("#-defaultpanoramafilenamemode asproject\r\n");
        sb.append("#-defaultpanoramafilename_custom \"\"\r\n");
        sb.append("#-defaultpanoramafoldermode projectfolder\r\n");
        sb.append("#-defaultpanoramafolder_custom \"\"\r\n");
        sb.append("#-userelativesourceimagepaths 1\r\n");
        sb.append("#-optimizeraskreinitialize 1\r\n");
        sb.append("#-applytemplate_lens 1\r\n");
        sb.append("#-applytemplate_crop 0\r\n");
        sb.append("#-applytemplate_imageparams 1\r\n");
        sb.append("#-applytemplate_mask 0\r\n");
        sb.append("#-applytemplate_optimizer 1\r\n");
        sb.append("#-applytemplate_panoramasettings 1\r\n");
        sb.append("#-applytemplate_projectsettings 1\r\n");
        sb.append("#-theoreticalhfov -1\r\n");
        sb.append("#-rect_compression_x 0\r\n");
        sb.append("#-rect_compression_y 0\r\n");
        sb.append("#-vedutismo_compression_x 1\r\n");
        sb.append("#-stereographic_compression 1\r\n");
        sb.append("\r\n# Panorama settings:\r\n");
        sb.append("# Uscita sferica 2:1. 27654x13827 e' il massimo misurato con 24 scatti 1x.\r\n");
        sb.append("# PTGui limita il JPEG a 25000 px per lato: se 'Create Panorama' rifiuta,\r\n");
        sb.append("# ridurre a 24000x12000 o cambiare il formato in TIFF. In alternativa,\r\n");
        sb.append("# 'Set optimum size' ricalcola la dimensione esatta da questo progetto.\r\n");
        sb.append("p w27654 h13827 f2 v360 u20 n\"JPEG g0 q95\"\r\n");
        sb.append("m g1 i0\r\n");
        sb.append("\r\n# input images:\r\n");
        for (Shot s : shots) {
            sb.append("o f0 y").append(fmt(s.pan)).append(" r0 p").append(fmt(s.tilt))
                    .append(" v").append(MEASURED_HFOV_DEG)
                    .append(" a0 b0 c0 d0 e0 g0 t0\r\n");
        }
        sb.append("\r\n# immagine demo sostituita da PTGui con la prima foto:\r\n");
        sb.append("#-dummyimage\r\n");
        sb.append("o w1 h1 y0 r0 p0 v").append(MEASURED_HFOV_DEG)
                .append(" a0 b0 c0 f2 d0 e0 g0 t0\r\n");
        sb.append("\r\n# Control points:\r\n\r\n");
        sb.append("# optimizer:\r\n");
        sb.append("v v d e r1 p1 d2 e2 y2 r2 p2\r\n");
        return sb.toString();
    }

    /** Papywizard XML: un'alternativa che PTGui importa con File > Import Papywizard. */
    public String toPapywizardXml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<papywizard>\n");
        sb.append("  <shoot>\n");
        for (Shot s : shots) {
            sb.append("    <pict roll=\"0\" pitch=\"").append(fmt(s.tilt))
                    .append("\" yaw=\"").append(fmt(s.pan)).append("\"/>\n");
        }
        sb.append("  </shoot>\n");
        sb.append("</papywizard>\n");
        return sb.toString();
    }

    /**
     * Progetto pano2vr pronto per l'uscita sferica.
     *
     * L'ingresso e' l'equirettangolare cucito da PTGui (o da PTGui a sua volta): pano2vr non
     * allinea le singole foto, le orienta. Il file punta quindi all'output dello stitch, con
     * nome relativo, cosi' basta mettere il .p2vr accanto alla foto cucita.
     *
     * L'output e' HTML5 con tile multirisoluzione da 512 px: e' la regola che pano2vr
     * consiglia per le sfere, e tiene la dimensione del progetto gestibile anche a 27k.
     */
    public String toPano2vrProject(String inputFileName) {
        String name = inputFileName == null || inputFileName.isEmpty()
                ? "PanoLuna_equirectangular.jpg" : inputFileName;
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<!-- Pano2VR project file - PanoLuna, Insta360 Luna Ultra -->\n");
        sb.append("<!--\n");
        sb.append("  Workflow: 24 scatti -> PTGui (PanoLuna_24scatti.pts) -> equirettangolare 2:1\n");
        sb.append("  Questo progetto punta gia' all'equirettangolare: aprirlo in pano2vr e\n");
        sb.append("  usare 'Crea tutto'. Per allineare le singole foto usare invece il\n");
        sb.append("  Papywizard XML (File > Import Papywizard in pano2vr Pro).\n");
        sb.append("-->\n");
        sb.append("<pano2vrconfig>\n");
        sb.append("  <panorama>\n");
        sb.append("    <input>\n");
        sb.append("      <patch/>\n");
        sb.append("      <filename>").append(xml(name)).append("</filename>\n");
        sb.append("      <type>auto</type>\n");
        sb.append("      <hfov>360</hfov>\n");
        sb.append("    </input>\n");
        sb.append("    <viewingparameter>\n");
        sb.append("      <pan>0</pan>\n");
        sb.append("      <tilt>0</tilt>\n");
        sb.append("      <fov>75</fov>\n");
        sb.append("    </viewingparameter>\n");
        sb.append("    <userdata>\n");
        sb.append("      <title>PanoLuna ").append(xml(name)).append("</title>\n");
        sb.append("      <description>24 scatti Insta360 Luna Ultra, allineati con PTGui</description>\n");
        sb.append("    </userdata>\n");
        sb.append("    <hotspots/>\n");
        sb.append("    <output>\n");
        sb.append("      <html>\n");
        sb.append("        <enabled>1</enabled>\n");
        sb.append("        <filename>output/PanoLuna_out.html</filename>\n");
        sb.append("        <template>normal.ggt</template>\n");
        sb.append("        <values>\n");
        sb.append("          <value id=\"bgcolor\">#000000</value>\n");
        sb.append("          <value id=\"showuserdata\">true</value>\n");
        sb.append("        </values>\n");
        sb.append("      </html>\n");
        sb.append("      <flash>\n");
        sb.append("        <enabled>0</enabled>\n");
        sb.append("      </flash>\n");
        sb.append("      <qtvr>\n");
        sb.append("        <enabled>0</enabled>\n");
        sb.append("      </qtvr>\n");
        sb.append("      <transformations>\n");
        sb.append("        <transformation>\n");
        sb.append("          <type>equirectangular</type>\n");
        sb.append("          <filename>output/$(n)_$(s).jpg</filename>\n");
        sb.append("          <quality>95</quality>\n");
        sb.append("          <width>27654</width>\n");
        sb.append("          <height>13827</height>\n");
        sb.append("          <tilewidth>512</tilewidth>\n");
        sb.append("          <tileheight>512</tileheight>\n");
        sb.append("          <multires>1</multires>\n");
        sb.append("          <format>jpg</format>\n");
        sb.append("        </transformation>\n");
        sb.append("      </transformations>\n");
        sb.append("    </output>\n");
        sb.append("    <sounds/>\n");
        sb.append("  </panorama>\n");
        sb.append("</pano2vrconfig>\n");
        return sb.toString();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String fmt(float value) {
        return String.format(java.util.Locale.US, "%.4f", value);
    }
}
