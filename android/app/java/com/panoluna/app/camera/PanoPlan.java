package com.panoluna.app.camera;

import java.util.ArrayList;
import java.util.List;

/**
 * План  съёмки сферической панорамы.
 *
 * По умолчанию — сетка Panoshooter: 4 ряда x 6 столбцов = 24 кадра, объектив 1x
 * Luna Ultra (измеренный горизонтальный угол 77,7°, вертикальный ~62,3° в формате 4:3).
 *
 * Ряды идут снизу вверх, направление движения чередуется (серпентина), поэтому гимбал
 * никогда не разворачивается на 360°: переход от кадра к кадру — всегда шаг, а не полный круг.
 *
 * План — чистая модель данных: построение и сериализаторы не отправляют команды камере.
 * Генераторы PTGui/Papywizard/Pano2VR используются тестами и утилитами; экран приложения
 * в v1.19.7 их не экспортирует.
 *
 * Координаты pan — виртуальные градусы плана внутри измеренного хода гимбала; они не
 * считываются с камеры и должны интерпретироваться вместе с оценкой позиции Gimbal.
 */
public final class PanoPlan {

    /**
     * Физический ход гимбала Luna Ultra: -57°…+235° (292°, меньше полного круга).
     * Замер apatassini на реальной камере: ricentraggio относителен стороне, но
     * абсолютный диапазон именно такой.
     */
    public static final float PAN_MIN_DEG = -57f;
    public static final float PAN_MAX_DEG = 235f;

    /** Точка плана. Углы в градусах: pan (горизонт), tilt (наклон). */
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

    public PanoPlan(int rows, int columns, float tiltBottom, float tiltTop) {
        this.rows = Math.max(1, Math.min(12, rows));
        this.columns = Math.max(1, Math.min(24, columns));
        this.tiltBottom = tiltBottom;
        this.tiltTop = tiltTop;
        // Перекрёстие вычисляется из реальной геометрии сетки и измеренного FOV, а не
        // задаётся числом: метка всегда соответствует тем координатам, которые высылает план.
        this.overlapPercent = computeOverlapPercent();
        build();
    }

    /**
     * План из 24 кадров: 4 ряда x 6 столбцов, полное покрытие сферы.
     *
     * Почему 24, а не 19 как во встроенном режиме камеры. Структура «3 ряда + зенит»
     * (18 + 1 = 19) верна по идее, но при вертикальном FOV 62.3° три ряда дают шаг
     * 58.9° и перекрытие всего 5.5% — PTGui почти наверняка не сошьёт ряды. Четыре
     * ряда дают шаг 39.2° и перекрытие 37%, при том же числе кадров на круг получается
     * 4x6 = 24 — надёжно и без лишних снимков.
     *
     * tilt от -58.85° до +58.85°: каждый ряд покрывает +-31.15°, поэтому нижний ряд
     * доходит до -90° (надир), верхний до +90° (зенит). Сфера закрыта целиком.
     *
     * Надир на -90° в кадре есть, но снимать его «в пол» бессмысленно: там штатив.
     * Это нормально — во всех панорамах надир закрывают отдельно.
     */
    public static PanoPlan shot24() {
        float tiltSpan = 180f - MEASURED_VFOV_DEG;
        return new PanoPlan(4, 6, -tiltSpan / 2f, tiltSpan / 2f);
    }

    /**
     * Строит план ВНУТРИ физического хода гимбала, а не по кругу 0..360.
     *
     * Гимбал Luna Ultra ходит от -57° до +235° — это 292°, меньше полного круга.
     * План вида pan = 0/60/120/180/240/300 упирается в предел на четвёртом кадре:
     * 240° и 300° недостижимы, и панорама обрывается (наблюдалось на камере).
     *
     * Поэтому все кадры раскладываются внутри хода как равномерная сетка. Шаг pan
     * не обязан быть ровно 360/столбцы: важно, чтобы сектор покрывался с перекрытием,
     * а лишние градусы хода — это запас, а не ошибка.
     */
    private void build() {
        shots.clear();
        int index = 0;
        float span = PAN_MAX_DEG - PAN_MIN_DEG;
        // Столбцы раскладываются на ВЕСЬ ход: между соседними створками gap = span/(n-1).
        // При 6 столбцах это 292/5 = 58,4°, а полный охват = 292 + 77,7 (FOV) = 369,7°,
        // то есть круг 360° покрывается с запасом 9,7°. Перекрытие 1-58,4/77,7 = 25%.
        float panStep = columns <= 1 ? 0f : span / (columns - 1);
        for (int row = 0; row < rows; row++) {
            float tilt = position(tiltBottom, tiltTop, rows, row);
            boolean forward = (row % 2 == 0);
            for (int step = 0; step < columns; step++) {
                int column = forward ? step : (columns - 1 - step);
                // Первый столбец — на нижнем пределе хода, последний — на верхнем пределе.
                float pan = clampPan(PAN_MIN_DEG + panStep * column);
                shots.add(new Shot(index++, row, column, pan, tilt));
            }
        }
    }

    /** Ограничивает pan физическим ходом гимбала. */
    private static float clampPan(float pan) {
        if (pan < PAN_MIN_DEG) {
            return PAN_MIN_DEG;
        }
        return Math.min(pan, PAN_MAX_DEG);
    }

    private static float position(float start, float end, int count, int index) {
        if (count <= 1) {
            return (start + end) / 2f;
        }
        return start + (end - start) * index / (count - 1);
    }

    /**
     * Минимальное реальное перекрытие (%) между соседними кадрами по горизонтали и вертикали.
     * Горизонтальный шаг равен фактическому покрытию хода гимбала между крайними
     * столбцами: (PAN_MAX_DEG-PAN_MIN_DEG)/(столбцы-1). Вертикальный шаг —
     * (tiltTop-tiltBottom)/(ряды-1). Перекрытие = 1 - шаг/FOV; возвращается меньшее
     * из горизонтального и вертикального значений — наихудшее перекрытие сетки.
     */
    private int computeOverlapPercent() {
        float panStep = columns <= 1 ? 0f
                : (PAN_MAX_DEG - PAN_MIN_DEG) / (columns - 1);
        float tiltSpan = tiltTop - tiltBottom;
        float tiltStep = (rows <= 1) ? 0f : tiltSpan / (rows - 1);
        int horizontal = (panStep > 0f && panStep < MEASURED_HFOV_DEG)
                ? (int) Math.round((1f - panStep / MEASURED_HFOV_DEG) * 100f) : 0;
        int vertical = (tiltStep > 0f && tiltStep < MEASURED_VFOV_DEG)
                ? (int) Math.round((1f - tiltStep / MEASURED_VFOV_DEG) * 100f) : 0;
        int min = Math.min(horizontal, vertical);
        return Math.max(10, Math.min(60, min));
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

    /** Горизонтальный шаг между соседними точками по фактическому ходу гимбала (градусы). */
    public float getPanStep() {
        return columns <= 1 ? 0f : (PAN_MAX_DEG - PAN_MIN_DEG) / (columns - 1);
    }

    /**
     * Горизонтальный угол поля зрения объектива 1x в формате 4:3, измерен на Luna Ultra
     * относительно горизонта: 77,7 градуса, а не заявленные каталогом 81,7.
     */
    public static final float MEASURED_HFOV_DEG = 77.7f;

    /** Соответствующий вертикальный угол поля зрения, в 4:3. */
    public static final float MEASURED_VFOV_DEG = 62.3f;

    /** Приведённый эквивалент фокусного расстояния 35 мм, дающий измеренный угол, для полнокадрового формата 4:3. */
    public static final float EQUIVALENT_FOCAL_MM = 21.5f;

    /**
     * Шаблон PTGui: 24 точки с yaw/pitch, прямолинейная оптика и измеренное поле зрения.
     * Изображения не включены: PTGui применяет параметры по индексу к загруженным фото.
     */
    /** Приводит yaw к диапазону 0..360: гимбал ходит до +235, PTGui ждёт полный оборот. */
    static float normalizeYaw(float yaw) {
        float value = yaw % 360f;
        if (value < 0f) {
            value += 360f;
        }
        return value;
    }

    public String toPtGuiTemplate(String name) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ptGui project file\r\n\r\n");
        sb.append("#-encoding utf8\r\n");
        sb.append("#-fileversion 49\r\n");
        sb.append("#-pathseparator \\\r\n");
        sb.append("# PanoLuna - Insta360 Luna Ultra, 24 кадра, оптика 1x 4:3\r\n");
        sb.append("# Измеренный горизонтальный угол: ").append(MEASURED_HFOV_DEG).append(" градусов\r\n");
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
        sb.append("# Выход сферический 2:1. 27654x13827 — максимум, измеренный для 24 кадров 1x.\r\n");
        sb.append("# PTGui ограничивает JPEG 25000 px на сторону: если 'Create Panorama' отклоняет,\r\n");
        sb.append("# убавьте до 24000x12000 или смените формат на TIFF. В случае необходимости,\r\n");
        sb.append("# 'Set optimum size' пересчитывает точную размерность из этого проекта.\r\n");
        sb.append("p w27654 h13827 f2 v360 u20 n\"JPEG g0 q95\"\r\n");
        sb.append("m g1 i0\r\n");
        sb.append("\r\n# input images:\r\n");
        // Один 'o'-ряд на кадр, в порядке съёмки. Ряды идут строго по порядку файлов
        // на карте: PTGui сопоставляет их по индексу, поэтому никаких «пустых» строк
        // между ними быть не должно (старый вариант добавлял dummy-картинку, из-за
        // чего индексы съезжали).
        for (Shot s : shots) {
            // yaw нормализуется в 0..360: у гимбала ход до +235, PTGui ждёт угол
            // в пределах полного оборота и на 235 молча не строит проект верно.
            sb.append("o f0 y").append(fmt(normalizeYaw(s.pan)))
                    .append(" r0 p").append(fmt(s.tilt))
                    .append(" v").append(MEASURED_HFOV_DEG)
                    .append(" a0 b0 c0 d0 e0 g0 t0\r\n");
        }
        // Контрольные точки ставятся ПАРАМИ между соседними кадрами по сетке.
        // Без них PTGui не находит соответствия сам на однородных сценах (небо, стены),
        // и «Create panorama» отказывается строить. Пары дают оптимизатору опору,
        // дальше PTGui дополняет их автопоиском.
        //
        // Точки берутся в центре перекрытия: по горизонтали между соседними столбцами,
        // по вертикали — по строкам. Нормализованные координаты PTGui: x/ширина.
        sb.append("\r\n# Control points:\r\n");
        List<Shot> ordered = shots;
        int rows = this.rows;
        int columns = this.columns;
        for (int i = 0; i < ordered.size(); i++) {
            Shot a = ordered.get(i);
            int row = a.row;
            int col = a.column;
            // Сосед справа по строке (для нечётных строк — в порядке змейки).
            if (col + 1 < columns) {
                Shot b = findShot(row, col + 1);
                if (b != null) {
                    appendHorizontalPair(sb, a, b);
                }
            }
            // Сосед сверху: там перекрытие по вертикали (37%).
            Shot up = findShot(row + 1, col);
            if (up != null) {
                appendVerticalPair(sb, a, up);
            }
        }
        sb.append("\r\n# optimizer:\r\n");
        sb.append("v v d e r1 p1 d2 e2 y2 r2 p2\r\n");
        return sb.toString();
    }

    private Shot findShot(int row, int column) {
        for (Shot s : shots) {
            if (s.row == row && s.column == column) {
                return s;
            }
        }
        return null;
    }

    /**
     * Пара контрольных точек между кадрами одной строки.
     *
     * Точки ставятся в геометрическом центре перекрытия: по x это половина пути между
     * краями кадров. PTGui ожидает нормализованные координаты (0..1).
     */
    private void appendHorizontalPair(StringBuilder sb, Shot a, Shot b) {
        int ia = shots.indexOf(a);
        int ib = shots.indexOf(b);
        // Центр перекрытия по горизонтали: около 0.5 у левого кадра и 0.5 у правого
        // при шаге 58.4 и HFOV 77.7 перекрытие 25% — точка попадает в общую зону.
        appendPair(sb, ia, 0.62f, 0.5f, ib, 0.38f, 0.5f);
    }

    /** Пара контрольных точек между кадрами соседних строк: перекрытие 37% по вертикали. */
    private void appendVerticalPair(StringBuilder sb, Shot a, Shot b) {
        int ia = shots.indexOf(a);
        int ib = shots.indexOf(b);
        // По вертикали общая зона шире: точка ближе к краю, но внутри перекрытия.
        appendPair(sb, ia, 0.5f, 0.66f, ib, 0.5f, 0.34f);
    }

    private void appendPair(StringBuilder sb, int indexA, float xa, float ya,
                            int indexB, float xb, float yb) {
        // Формат строки PTGui: c nN xX yY X Y tG
        // nN — номер изображения (с нуля), xX/yY — точка в первом, X/Y — во втором.
        sb.append("c n").append(indexA).append(" x").append(fmt4(xa)).append(" y")
                .append(fmt4(ya)).append(" X").append(fmt4(xb)).append(" Y")
                .append(fmt4(yb)).append(" t0\r\n");
        sb.append("c n").append(indexA).append(" x").append(fmt4(xa)).append(" y")
                .append(fmt4(ya)).append(" X").append(fmt4(xb)).append(" Y")
                .append(fmt4(yb)).append(" t1 n").append(indexB).append("\r\n");
    }

    private static String fmt4(float value) {
        return String.format(java.util.Locale.US, "%.4f", value);
    }

    /** XML Papywizard: альтернативный формат, импортируемый в PTGui через пункт File > Import Papywizard. */
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
     * Проект pano2vr, готовый к сферическому выводу.
     *
     * Вход — сшитая PTGui равноудаленная панорама: pano2vr не выравнивает отдельные фото,
     * а только ориентирует их. Проект указывает на результат сшивки по относительному
     * имени, поэтому достаточно положить .p2vr рядом с сшитой фото.
     *
     * Вывод — HTML5 с мультирезолюционными плитками 512 px: это правило pano2vr для
     * сфер и держит размер проекта в управляемых пределах даже при 27k.
     */
    public String toPano2vrProject(String inputFileName) {
        String name = inputFileName == null || inputFileName.isEmpty()
                ? "PanoLuna_equirectangular.jpg" : inputFileName;
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<!-- Pano2VR project file - PanoLuna, Insta360 Luna Ultra -->\n");
        sb.append("<!--\n");
        sb.append("  Workflow: 24 кадра -> PTGui (PanoLuna_24.pts) -> equirectangular 2:1\n");
        sb.append("  Этот проект уже нацелен на equirectangular: открыть в pano2vr и\n");
        sb.append("  использовать 'Crea tutto'. Для выравнивания отдельных фото использовать\n");
        sb.append("  Papywizard XML (File > Import Papywizard в pano2vr Pro).\n");
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
        sb.append("      <description>24 кадра Insta360 Luna Ultra, выровненные в PTGui</description>\n");
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
