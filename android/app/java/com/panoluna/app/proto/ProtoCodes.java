package com.panoluna.app.proto;

/**
 * Константы управляющего протокола Insta360 (пространство имён {@code insta360.messages}).
 *
 * Числа не придуманы: они взяты из извлечённых файлов .proto проекта
 * RigacciOrg/insta360-wifi-api и измерений Luna Ultra, опубликованных Persoft
 * (Insta360luna, лицензия MIT). Значения, подтверждённые измерениями, отмечены отдельно.
 */
public final class ProtoCodes {

    private ProtoCodes() {
    }

    // ---- Команды телефон → камера (PHONE_COMMAND_*) ----
    public static final int START_LIVE_STREAM = 1;
    public static final int STOP_LIVE_STREAM = 2;
    public static final int TAKE_PICTURE = 3;
    public static final int START_CAPTURE = 4;
    public static final int STOP_CAPTURE = 5;
    public static final int GET_FILE_EXTRA = 11;
    public static final int SET_FILE_EXTRA = 16;
    public static final int GET_FILE_LIST = 13;
    public static final int GET_MINI_THUMBNAIL = 30;
    /** SET_ACCESS_CAMERA_FILE_STATE: сообщает камере значение access_state. */
    public static final int SET_ACCESS_CAMERA_FILE_STATE = 118;
    public static final int SET_OPTIONS = 7;
    public static final int GET_OPTIONS = 8;
    public static final int SET_PHOTOGRAPHY_OPTIONS = 9;
    public static final int GET_CURRENT_CAPTURE_STATUS = 15;
    public static final int GET_TIMELAPSE_OPTIONS = 17;
    public static final int SET_TIMELAPSE_OPTIONS = 18;
    public static final int START_TIMELAPSE = 22;
    public static final int STOP_TIMELAPSE = 23;
    /** PHONE_COMMAND_GIMBAL_CONTROL, проверено на Luna Ultra. */
    public static final int GIMBAL_CONTROL = 0x00E2;

    /** Luna Ultra всегда отвечает кодом 200; исходная команда определяется по requestId. */
    public static final int RESPONSE_OK = 200;
    public static final int RESPONSE_ERROR = 500;

    // ---- Уведомления камера → телефон (CAMERA_NOTIFICATION_*) ----
    public static final int NOTIFICATION_BEGIN = 8192;
    public static final int NOTIFICATION_BATTERY_UPDATE = 8195;
    public static final int NOTIFICATION_BATTERY_LOW = 8196;
    public static final int NOTIFICATION_STORAGE_UPDATE = 8198;
    public static final int NOTIFICATION_STORAGE_FULL = 8199;
    public static final int NOTIFICATION_CAPTURE_STOPPED = 8201;
    public static final int NOTIFICATION_TAKE_PICTURE_STATE = 8202;
    /** Единственный код, который на Luna Ultra (прошивка 1.0.288) сообщает об окончании записи. */
    public static final int NOTIFICATION_CURRENT_CAPTURE_STATUS = 8208;
    public static final int NOTIFICATION_TIMELAPSE_STATUS_UPDATE = 8210;
    public static final int NOTIFICATION_EXPOSURE_UPDATE = 8220;
    public static final int NOTIFICATION_GIMBAL_SPEED = 0x206A;
    public static final int NOTIFICATION_PTZ_STATE_OBSERVED = 8302;

    /** Значения OptionType, используемые приложением. */
    public static final class OptionType {
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

    /** Номера полей Options. */
    public static final class OptionsField {
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

    /** TakePicture.Mode: 0 — обычный режим, 1 — AEB, 3 — серия. Это не CaptureMode. */
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
        /** Состояние съёмки и записи снимка на Luna Ultra; длится около 5 секунд. */
        public static final int SINGLE_SHOOTING = 4;
        public static final int STATIC_TIMELAPSE_SHOOTING = 11;
        public static final int INTERVAL_VIDEO_CAPTURE = 12;

        public static boolean isBusy(int value) {
            return value != NOT_CAPTURE;
        }
    }

    /** Коды Error.ErrorCode. */
    public static final class ErrorCode {
        public static final int UNKNOWN_ERROR = 0;
        public static final int UNKNOWN_MSG_CODE = 1;
        public static final int UNKNOWN_MSG_PAYLOAD = 2;
        public static final int EXECUTE_ERROR = 3;
        public static final int SYSTEM_BUSY = 5;
    }

    /** Действия гимбала в поле 1 GIMBAL_CONTROL, проверенные на Luna Ultra. */
    public static final class GimbalAction {
        public static final int MOVE = 1;
        public static final int BACK_CENTER = 2;
        public static final int SELFIE_TOGGLE = 3;
    }

    /** Поля GetOptions/SetOptions. */
    public static final int FIELD_OPTION_TYPES = 1;
    public static final int FIELD_OPTIONS_VALUE = 2;

    /** SetAccessCameraFileState.access_state: 5 — приложение показывает live view. */
    public static final class AccessState {
        public static final int LIVE_VIEW = 5;
    }

    /** Номера полей StartLiveStream (insta360.messages.StartLiveStream). */
    public static final class StartLiveStreamField {
        public static final int ENABLE_VIDEO = 2;
        public static final int VIDEO_BITRATE = 6;
        public static final int RESOLUTION = 7;
        public static final int ENABLE_GYRO = 8;
        public static final int VIDEO_BITRATE_1 = 9;
        public static final int RESOLUTION_1 = 10;
    }

    /** VideoResolution: 9 = 1440x720p30, 18 = 480x240p30 (проверенный вариант сообщения). */
    public static final class VideoResolution {
        public static final int RES_1440_720P30 = 9;
        public static final int RES_480_240P30 = 18;
    }
}
