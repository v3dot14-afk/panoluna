package com.panoluna.app.proto;

import java.util.List;

/**
 * Composizione dei corpi protobuf dei comandi e lettura delle risposte.
 *
 * Funzioni pure: byte in uscita, nessuna sessione. I corpi sono gli stessi osservati nel
 * traffico tra l'app Insta360 e la Luna Ultra.
 */
public final class Messages {

    private Messages() {
    }

    /** GetOptions { repeated OptionType option_types = 1 } */
    public static byte[] getOptions(int... types) {
        Proto.Writer w = new Proto.Writer();
        for (int type : types) {
            w.int32(ProtoCodes.FIELD_OPTION_TYPES, type);
        }
        return w.toByteArray();
    }

    /** SetOptions { repeated OptionType option_types = 1; Options value = 2 } */
    public static byte[] setOption(int optionType, int field, int value) {
        Proto.Writer nested = new Proto.Writer();
        nested.int32(field, value);
        Proto.Writer w = new Proto.Writer();
        w.int32(ProtoCodes.FIELD_OPTION_TYPES, optionType);
        w.message(ProtoCodes.FIELD_OPTIONS_VALUE, nested.toByteArray());
        return w.toByteArray();
    }

    /** TakePicture { Mode mode = 1; bool isInstaPanoEnabled = 5 } */
    public static byte[] takePicture(int mode) {
        Proto.Writer w = new Proto.Writer();
        if (mode != 0) {
            w.int32(1, mode);
        }
        return w.toByteArray();
    }

    /**
     * TakePicture per una panoramica: mode NORMAL (0, omesso) e
     * isInstaPanoEnabled=true sul campo 5. La camera deve essere già in modalità
     * pano (photo_sub_mode = PHOTO_INSTA_PANO) per attivare lo scatto a panoramica.
     */
    public static byte[] takePicturePano() {
        Proto.Writer w = new Proto.Writer();
        w.bool(5, true);
        return w.toByteArray();
    }

    /** SetAccessCameraFileState { AccessState access_state = 1 }: 5 = live view. */
    public static byte[] setAccessCameraFileState(int accessState) {
        Proto.Writer w = new Proto.Writer();
        w.int32(1, accessState);
        return w.toByteArray();
    }

    /**
     * StartLiveStream { bool enable_video = 2; uint32 video_bitrate = 6;
     * VideoResolution resolution = 7; bool enable_gyro = 8;
     * uint32 video_bitrate1 = 9; VideoResolution resolution1 = 10 }.
     *
     * Corpo identico a quello della referenz-app: attiva la trasmissione del
     * preview, che è condizione per far rispondere il gimbal.
     */
    public static byte[] startLiveStream() {
        Proto.Writer w = new Proto.Writer();
        w.int32(ProtoCodes.StartLiveStreamField.ENABLE_VIDEO, 1);
        w.int32(ProtoCodes.StartLiveStreamField.VIDEO_BITRATE, 40);
        w.int32(ProtoCodes.StartLiveStreamField.RESOLUTION, ProtoCodes.VideoResolution.RES_1440_720P30);
        w.int32(ProtoCodes.StartLiveStreamField.ENABLE_GYRO, 1);
        w.int32(ProtoCodes.StartLiveStreamField.VIDEO_BITRATE_1, 40);
        w.int32(ProtoCodes.StartLiveStreamField.RESOLUTION_1, ProtoCodes.VideoResolution.RES_480_240P30);
        return w.toByteArray();
    }

    /** Imposta photo_sub_mode (PHOTO_SINGLE o PHOTO_INSTA_PANO) via SET_OPTIONS. */
    public static byte[] setPhotoSubMode(int subMode) {
        return setOption(ProtoCodes.OptionType.PHOTO_SUB_MODE,
                ProtoCodes.OptionsField.PHOTO_SUB_MODE, subMode);
    }

    /**
     * Imposta la proporzione della panoramica in-camera: PANO_ASPECT (98) su
     * function_mode NORMAL_POWER_PANO_IMAGE. 1 = 360-sfere, 4 = 2:1.
     *
     * Без этого множества официальная приложение не запускает встроенный панорамный
     * цикл камеры (19 кадров + встроенный stitching): камера остаётся в INSTA_PANO, но
     * не знает, какой panorama делать. Контролирует официальный Insta360-приложение.
     */
    public static byte[] setPanoAspect(int aspect, int functionMode) {
        return setPhotographyOption(
                ProtoCodes.PhotographyOptionType.PANO_ASPECT,
                ProtoCodes.PhotographyOptionsField.PANO_ASPECT,
                aspect,
                functionMode);
    }

    /**
     * SetPhotographyOptions { repeated PhotographyOptionType option_types = 1;
     * PhotographyOptions value = 2; FunctionMode function_mode = 3 }.
     *
     * Il function_mode non e' decorativo: le opzioni sono memorizzate per modalita'.
     */
    public static byte[] setPhotographyOption(int optionType, int field, int value, int functionMode) {
        Proto.Writer nested = new Proto.Writer();
        nested.int32(field, value);
        Proto.Writer w = new Proto.Writer();
        w.int32(1, optionType);
        w.message(2, nested.toByteArray());
        w.int32(3, functionMode);
        return w.toByteArray();
    }

    /** Cambiare la risoluzione foto: PHOTO_SIZE_ID con l'enum PhotoSize. */
    public static byte[] setPhotoSize(int photoSizeCode, int functionMode) {
        return setPhotographyOption(
                ProtoCodes.PhotographyOptionType.PHOTO_SIZE_ID,
                ProtoCodes.PhotographyOptionsField.PHOTO_SIZE_ID,
                photoSizeCode,
                functionMode);
    }

    /**
     * Esposizione: il firmware vuole i secondi reali, non l'enum otturatore.
     * shutterSeconds == 0 lascia l'otturatore automatico.
     */
    public static byte[] setExposure(int program, int iso, double shutterSeconds, int functionMode) {
        Proto.Writer exposure = new Proto.Writer();
        exposure.int32(ProtoCodes.ExposureOptionsField.PROGRAM, program);
        if (iso > 0) {
            exposure.int32(ProtoCodes.ExposureOptionsField.ISO, iso);
        }
        if (shutterSeconds > 0.0) {
            exposure.double64(ProtoCodes.ExposureOptionsField.SHUTTER_SPEED, shutterSeconds);
        }
        Proto.Writer nested = new Proto.Writer();
        nested.message(ProtoCodes.PhotographyOptionsField.STILL_EXPOSURE_OPTIONS, exposure.toByteArray());
        Proto.Writer w = new Proto.Writer();
        w.int32(1, ProtoCodes.PhotographyOptionType.STILL_EXPOSURE_OPTIONS);
        w.message(2, nested.toByteArray());
        w.int32(3, functionMode);
        return w.toByteArray();
    }

    public static byte[] setExposureBias(int thirds, int functionMode) {
        return setPhotographyOption(
                ProtoCodes.PhotographyOptionType.EXPOSURE_BIAS,
                ProtoCodes.PhotographyOptionsField.EXPOSURE_BIAS,
                thirds,
                functionMode);
    }

    public static byte[] setBrightness(int value, int functionMode) {
        return setPhotographyOption(
                ProtoCodes.PhotographyOptionType.BRIGHTNESS,
                ProtoCodes.PhotographyOptionsField.BRIGHTNESS,
                value,
                functionMode);
    }

    /** WhiteBalance: 0 = automatico, altrimenti MANUAL_KELVIN con la temperatura nel campo 39. */
    public static byte[] setWhiteBalance(int kelvin, int functionMode) {
        Proto.Writer nested = new Proto.Writer();
        nested.int32(ProtoCodes.PhotographyOptionsField.WHITE_BALANCE,
                kelvin == 0 ? ProtoCodes.WhiteBalance.AUTO : ProtoCodes.WhiteBalance.MANUAL_KELVIN);
        nested.int32(ProtoCodes.PhotographyOptionsField.WHITE_BALANCE_VALUE, kelvin);
        Proto.Writer w = new Proto.Writer();
        w.int32(1, ProtoCodes.PhotographyOptionType.WHITE_BALANCE);
        w.int32(1, ProtoCodes.PhotographyOptionType.WHITE_BALANCE_VALUE);
        w.message(2, nested.toByteArray());
        w.int32(3, functionMode);
        return w.toByteArray();
    }

    /** Lo zoom e' un double e viaggia sul campo ZOOM_SCALE. */
    public static byte[] setZoomScale(int scale, int functionMode) {
        Proto.Writer nested = new Proto.Writer();
        nested.double64(ProtoCodes.PhotographyOptionsField.ZOOM_SCALE, (double) scale);
        Proto.Writer w = new Proto.Writer();
        w.int32(1, ProtoCodes.PhotographyOptionType.ZOOM_SCALE);
        w.message(2, nested.toByteArray());
        w.int32(3, functionMode);
        return w.toByteArray();
    }

    public static byte[] setRawCapture(int rawType, int functionMode) {
        return setPhotographyOption(
                ProtoCodes.PhotographyOptionType.RAW_CAPTURE_TYPE,
                ProtoCodes.PhotographyOptionsField.RAW_CAPTURE_TYPE,
                rawType,
                functionMode);
    }

    /** GetPhotographyOptions { repeated PhotographyOptionType option_types = 1; FunctionMode = 2 } */
    public static byte[] getPhotographyOptions(int[] optionTypes, int functionMode) {
        Proto.Writer w = new Proto.Writer();
        for (int type : optionTypes) {
            w.int32(1, type);
        }
        w.int32(2, functionMode);
        return w.toByteArray();
    }

    /**
     * Vettore della levetta del gimbal.
     *
     * L'interfaccia usa orizzontale/verticale in -100..100; la camera usa gli assi ruotati
     * device_x = verticale, device_y = orizzontale. Il vettore nullo produce esattamente
     * {@code 08 01 12 00} ed e' il comando di stop.
     */
    public static byte[] gimbalMove(int horizontal, int vertical) {
        horizontal = clamp(horizontal, -100, 100);
        vertical = clamp(vertical, -100, 100);
        Proto.Writer axes = new Proto.Writer();
        if (vertical != 0) {
            axes.sint32(1, vertical);
        }
        if (horizontal != 0) {
            axes.sint32(2, horizontal);
        }
        Proto.Writer w = new Proto.Writer();
        w.int32(1, ProtoCodes.GimbalAction.MOVE);
        w.message(2, axes.toByteArray());
        return w.toByteArray();
    }

    /** Azione del gimbal senza assi: il solo campo 1. */
    public static byte[] gimbalAction(int action) {
        Proto.Writer w = new Proto.Writer();
        w.int32(1, action);
        return w.toByteArray();
    }

    /** Scrittura del livello hardware del gimbal: 1 lento, 2 medio, 3 veloce. */
    public static byte[] setGimbalSpeed(int level) {
        Proto.Writer inner = new Proto.Writer();
        inner.int32(2, level);
        Proto.Writer middle = new Proto.Writer();
        middle.message(0x55, inner.toByteArray());
        Proto.Writer w = new Proto.Writer();
        w.int32(1, 0x55);
        w.message(2, middle.toByteArray());
        w.int32(3, 0x06);
        return w.toByteArray();
    }

    /** GetFileList { MediaType media_type = 1; uint32 start = 2; uint32 limit = 3 } */
    public static byte[] getFileList(int mediaType, int start, int limit) {
        Proto.Writer w = new Proto.Writer();
        w.int32(1, mediaType);
        w.int32(2, start);
        w.int32(3, limit);
        return w.toByteArray();
    }

    /** DeleteFiles { repeated string uri = 1 } */
    public static byte[] deleteFiles(List<String> uris) {
        Proto.Writer w = new Proto.Writer();
        for (String uri : uris) {
            w.string(1, uri);
        }
        return w.toByteArray();
    }

    /** GetFileExtra { string uri = 1; uint32 extra_type = 2 } */
    public static byte[] getFileExtra(String uri, int extraType) {
        Proto.Writer w = new Proto.Writer();
        w.string(1, uri);
        w.int32(2, extraType);
        return w.toByteArray();
    }

    public static byte[] setFileExtra(String uri, int extraType, byte[] data) {
        Proto.Writer w = new Proto.Writer();
        w.string(1, uri);
        w.int32(2, extraType);
        w.bytes(3, data);
        return w.toByteArray();
    }

    // ---- Lettura delle risposte ----

    /** Il percorso della foto appena scritta: TakePictureResponse.image.uri. */
    public static String photoUri(byte[] payload) {
        Proto.Reader r = new Proto.Reader(payload);
        String uri = r.stringOrNull(ProtoCodes.PhotoField.IMAGE, ProtoCodes.PhotoField.URI);
        if (uri == null || uri.length() == 0) {
            return null;
        }
        return uri;
    }

    /** CameraCaptureStatus: state=1, capture_time=2. Nella risposta e' annidato nel campo 1. */
    public static int captureState(byte[] payload, boolean nested) {
        Proto.Reader r = new Proto.Reader(payload);
        Integer state = nested
                ? r.intOrNull(1, ProtoCodes.CaptureStatusField.STATE)
                : r.intOrNull(ProtoCodes.CaptureStatusField.STATE);
        return state == null ? ProtoCodes.CaptureState.NOT_CAPTURE : state;
    }

    public static Integer captureSeconds(byte[] payload, boolean nested) {
        Proto.Reader r = new Proto.Reader(payload);
        return nested
                ? r.intOrNull(1, ProtoCodes.CaptureStatusField.CAPTURE_TIME)
                : r.intOrNull(ProtoCodes.CaptureStatusField.CAPTURE_TIME);
    }

    /** GetFileListResp { repeated string uri = 1; uint32 total_count = 2 } */
    public static String[] fileListUris(byte[] payload) {
        Proto.Reader r = new Proto.Reader(payload);
        java.util.List<FieldList> list = new java.util.ArrayList<FieldList>();
        for (Proto.Field f : r.fields()) {
            if (f.number == 1 && f.isLength()) {
                list.add(new FieldList(f.asString()));
            }
        }
        String[] out = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i).value;
        }
        return out;
    }

    public static Integer fileListTotal(byte[] payload) {
        return new Proto.Reader(payload).intOrNull(2);
    }

    /** Options: il valore e' nel campo 2 di GetOptionsResp. */
    public static byte[] optionsValue(byte[] payload) {
        Proto.Field f = new Proto.Reader(payload).find(ProtoCodes.FIELD_OPTIONS_VALUE);
        return f == null ? new byte[0] : f.length;
    }

    private static final class FieldList {
        final String value;

        FieldList(String value) {
            this.value = value;
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
