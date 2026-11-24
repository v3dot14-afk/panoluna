package com.panoluna.app.proto;

/**
 * Costanti del protocollo di controllo Insta360 (namespace {@code insta360.messages}).
 *
 * I numeri NON sono inventati: provengono dall'estrazione dei .proto del progetto
 * RigacciOrg/insta360-wifi-api e da misure sulla Luna Ultra riportate da Persoft
 * (Insta360luna, licenza MIT). Dove un valore e' stato misurato e non dedotto, e' scritto.
 */
public final class ProtoCodes {

    private ProtoCodes() {
    }

    // ---- Comandi telefono -> camera (PHONE_COMMAND_*) ----
    public static final int START_LIVE_STREAM = 1;
    public static final int STOP_LIVE_STREAM = 2;
    public static final int TAKE_PICTURE = 3;
    public static final int START_CAPTURE = 4;
    public static final int STOP_CAPTURE = 5;
    public static final int GET_FILE_EXTRA = 11;
    public static final int SET_FILE_EXTRA = 16;
    public static final int DELETE_FILES = 12;
    public static final int GET_FILE_LIST = 13;
    public static final int GET_MINI_THUMBNAIL = 30;
    /** SET_ACCESS_CAMERA_FILE_STATE: annuncia allo stato access_state. */
    public static final int SET_ACCESS_CAMERA_FILE_STATE = 118;
    public static final int SET_OPTIONS = 7;
    public static final int GET_OPTIONS = 8;
    public static final int SET_PHOTOGRAPHY_OPTIONS = 9;
    public static final int GET_PHOTOGRAPHY_OPTIONS = 10;
    public static final int GET_CURRENT_CAPTURE_STATUS = 15;
    public static final int GET_TIMELAPSE_OPTIONS = 17;
    public static final int SET_TIMELAPSE_OPTIONS = 18;
    public static final int START_TIMELAPSE = 22;
    public static final int STOP_TIMELAPSE = 23;
    /** PHONE_COMMAND_GIMBAL_CONTROL, confermato sulla Luna Ultra. */
    public static final int GIMBAL_CONTROL = 0x00E2;

    /** La Luna Ultra risponde sempre 200, con il comando solo nel requestId. */
    public static final int RESPONSE_OK = 200;
    public static final int RESPONSE_ERROR = 500;

    // ---- Notifiche camera -> telefono (CAMERA_NOTIFICATION_*) ----
    public static final int NOTIFICATION_BEGIN = 8192;
    public static final int NOTIFICATION_BATTERY_UPDATE = 8195;
    public static final int NOTIFICATION_BATTERY_LOW = 8196;
    public static final int NOTIFICATION_STORAGE_UPDATE = 8198;
    public static final int NOTIFICATION_STORAGE_FULL = 8199;
    public static final int NOTIFICATION_CAPTURE_STOPPED = 8201;
    public static final int NOTIFICATION_TAKE_PICTURE_STATE = 8202;
    /** Unico codice che su Luna Ultra (firmware 1.0.288) dice davvero quando la scrittura finisce. */
    public static final int NOTIFICATION_CURRENT_CAPTURE_STATUS = 8208;
    public static final int NOTIFICATION_TIMELAPSE_STATUS_UPDATE = 8210;
    public static final int NOTIFICATION_EXPOSURE_UPDATE = 8220;
    public static final int NOTIFICATION_GIMBAL_SPEED = 0x206A;
    public static final int NOTIFICATION_PTZ_STATE_OBSERVED = 8302;

    /** Enum PhotoSize: la risoluzione con cui la camera scatta. */
    public static final class PhotoSize {
        public static final int STANDARD_12MP = 3;   // 4000x3000
        public static final int ULTRA_48MP = 7;      // 8000x6000
        public static final int ULTRA_37MP = 12;     // 5952x2976 (2:1) - non usato

        public static String label(int value) {
            switch (value) {
                case 0: return "6912x3456";
                case 1: return "6272x3136";
                case 2: return "6080x3040";
                case 3: return "Standard 12 MP (4000x3000)";
                case 4: return "4000x2250";
                case 5: return "5212x3542";
                case 6: return "5312x2988";
                case 7: return "Ultra 48 MP (8000x6000)";
                case 8: return "8000x4500";
                case 9: return "2976x2976";
                case 10: return "5984x5984";
                case 11: return "11968x5984";
                case 12: return "5952x2976";
                default: return "sconosciuta (#" + value + ")";
            }
        }
    }

    /** Valori di OptionType usati dall'app. */
    public static final class OptionType {
        public static final int PHOTO_SIZE = 2;
        public static final int CAPTURE_TIME_LIMIT = 7;
        public static final int BATTERY_STATUS = 11;
        public static final int LOCAL_TIME = 12;
        public static final int TIME_ZONE = 13;
        public static final int SERIAL_NUMBER = 15;
        public static final int STORAGE_STATE = 20;
        public static final int FIRMWARE_REVISION = 30;
        public static final int WIFI_INFO = 36;
        public static final int PHOTO_SUB_MODE = 40;
        public static final int VIDEO_SUB_MODE = 41;
        public static final int CAMERA_TYPE = 48;
    }

    /** Numeri di campo di Options. */
    public static final class OptionsField {
        public static final int PHOTO_SIZE = 2;
        public static final int BATTERY_STATUS = 11;
        public static final int SERIAL_NUMBER = 15;
        public static final int STORAGE_STATE = 20;
        public static final int FIRMWARE_REVISION = 30;
        public static final int WIFI_INFO = 36;
        public static final int PHOTO_SUB_MODE = 40;
        public static final int VIDEO_SUB_MODE = 41;
        public static final int CAMERA_TYPE = 48;
    }

    public static final class WifiInfoField {
        public static final int SSID = 1;
        public static final int PASSWORD = 2;
    }

    public static final class MediaType {
        public static final int VIDEO = 0;
        public static final int PHOTO = 1;
        public static final int VIDEO_AND_PHOTO = 2;
        public static final int JPG = 5;
    }

    public static final class PhotoSubMode {
        public static final int SINGLE = 0;
        public static final int INSTA_PANO = 8;
        public static final int NONE = 100;
    }

    public static final class PanoAspect {
        public static final int SPHERE_360 = 1;
        public static final int RATIO_2_1 = 4;
    }

    public static final class FunctionMode {
        public static final int MOBILE_TIMELAPSE = 2;
        public static final int NORMAL_IMAGE = 6;
        public static final int NORMAL_VIDEO = 7;
        public static final int NORMAL_POWER_PANO_IMAGE = 14;
    }

    public static final class PhotographyOptionType {
        public static final int BRIGHTNESS = 2;
        public static final int SHARPNESS = 6;
        public static final int EXPOSURE_BIAS = 7;
        public static final int EXPOSURE_MODE = 8;
        public static final int WHITE_BALANCE = 13;
        public static final int VIDEO_GAMMA_MODE = 18;
        public static final int STILL_EXPOSURE_OPTIONS = 20;
        public static final int RAW_CAPTURE_TYPE = 25;
        public static final int PHOTO_SIZE_ID = 30;
        public static final int RECORD_RESOLUTION = 31;
        public static final int COLOR_MODE = 35;
        public static final int WHITE_BALANCE_VALUE = 39;
        public static final int ZOOM_SCALE = 53;
        public static final int PANO_ASPECT = 98;
        public static final int FILTER_INTENSITY = 104;
        // Фокус сюда не добавлен намеренно. Имена AF_LENS_INFO / AUTO_FOCUS_MODE
        // в прошивке есть, но числа к ним зашифрованы (AppShield). Перебор номеров
        // вслепую бесполезен: камера отвечает 200 на ЛЮБОЙ номер, поэтому «успех»
        // ничего не подтверждает. Режим автофокуса ставится на самой камере.
    }

    public static final class PhotographyOptionsField {
        public static final int BRIGHTNESS = 2;
        public static final int SHARPNESS = 6;
        public static final int EXPOSURE_BIAS = 7;
        public static final int EXPOSURE_MODE = 8;
        public static final int WHITE_BALANCE = 13;
        public static final int STILL_EXPOSURE_OPTIONS = 20;
        public static final int RAW_CAPTURE_TYPE = 25;
        public static final int PHOTO_SIZE_ID = 30;
        public static final int RECORD_RESOLUTION = 31;
        public static final int COLOR_MODE = 35;
        public static final int WHITE_BALANCE_VALUE = 39;
        public static final int ZOOM_SCALE = 53;
        public static final int PANO_ASPECT = 98;
        public static final int FILTER_INTENSITY = 104;
    }

    public static final class ExposureOptionsField {
        public static final int PROGRAM = 1;
        public static final int ISO = 2;
        public static final int SHUTTER_SPEED = 3;
    }

    public static final class ExposureProgram {
        public static final int AUTO = 0;
        public static final int ISO_PRIORITY = 1;
        public static final int SHUTTER_PRIORITY = 2;
        public static final int MANUAL = 3;
    }

    public static final class WhiteBalance {
        public static final int AUTO = 0;
        public static final int MANUAL_KELVIN = 3;
    }

    public static final class RawCaptureType {
        public static final int OFF = 0;
        public static final int DNG = 1;
    }

    /** TakePicture.Mode: 0 normale, 1 AEB, 3 burst. NON e' CaptureMode. */
    public static final class TakePictureMode {
        public static final int NORMAL = 0;
        public static final int AEB = 1;
        public static final int BURST = 3;
    }

    public static final class BatteryField {
        public static final int POWER_TYPE = 1;
        public static final int BATTERY_LEVEL = 2;
        public static final int BATTERY_SCALE = 3;
    }

    public static final class StorageField {
        public static final int CARD_STATE = 1;
        public static final int FREE_SPACE = 2;
        public static final int TOTAL_SPACE = 3;
    }

    public static final class CaptureStatusField {
        public static final int STATE = 1;
        public static final int CAPTURE_TIME = 2;
        public static final int CAPTURE_NUMS = 3;
    }

    public static final class PhotoField {
        public static final int IMAGE = 1;
        public static final int URI = 1;
        public static final int FILE_SIZE = 2;
    }

    public static final class CaptureState {
        public static final int NOT_CAPTURE = 0;
        public static final int NORMAL_CAPTURE = 1;
        public static final int TIMELAPSE_CAPTURE = 2;
        /** Stato in cui la Luna Ultra scatta una foto e la scrive: dura ~5 secondi. */
        public static final int SINGLE_SHOOTING = 4;
        public static final int STATIC_TIMELAPSE_SHOOTING = 11;
        public static final int INTERVAL_VIDEO_CAPTURE = 12;

        public static boolean isBusy(int value) {
            return value != NOT_CAPTURE;
        }
    }

    /** Codici di Error.ErrorCode. */
    public static final class ErrorCode {
        public static final int UNKNOWN_ERROR = 0;
        public static final int UNKNOWN_MSG_CODE = 1;
        public static final int UNKNOWN_MSG_PAYLOAD = 2;
        public static final int EXECUTE_ERROR = 3;
        public static final int SYSTEM_BUSY = 5;
    }

    /** Azioni del gimbal trasportate dal campo 1 di GIMBAL_CONTROL, misurate sulla Luna Ultra. */
    public static final class GimbalAction {
        public static final int MOVE = 1;
        public static final int BACK_CENTER = 2;
        public static final int SELFIE_TOGGLE = 3;
    }

    /** Campi di GetOptions/SetOptions. */
    public static final int FIELD_OPTION_TYPES = 1;
    public static final int FIELD_OPTIONS_VALUE = 2;

    /** SetAccessCameraFileState.access_state: 5 = l'app mostra il live view. */
    public static final class AccessState {
        public static final int LIVE_VIEW = 5;
    }

    /** Numeri di campo di StartLiveStream (insta360.messages.StartLiveStream). */
    public static final class StartLiveStreamField {
        public static final int ENABLE_VIDEO = 2;
        public static final int VIDEO_BITRATE = 6;
        public static final int RESOLUTION = 7;
        public static final int ENABLE_GYRO = 8;
        public static final int VIDEO_BITRATE_1 = 9;
        public static final int RESOLUTION_1 = 10;
    }

    /** VideoResolution: 9 = 1440x720p30, 18 = 480x240p30 (il corpo noto-buono). */
    public static final class VideoResolution {
        public static final int RES_1440_720P30 = 9;
        public static final int RES_480_240P30 = 18;
    }
}
