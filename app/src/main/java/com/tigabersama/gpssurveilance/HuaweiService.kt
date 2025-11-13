package com.tigabersama.gpssurveilance

fun isHuaweiDevice(): Boolean {
    return try {
        Class.forName("com.huawei.hms.api.HuaweiApiAvailability")
        true
    } catch (e: ClassNotFoundException) {
        false
    }
}