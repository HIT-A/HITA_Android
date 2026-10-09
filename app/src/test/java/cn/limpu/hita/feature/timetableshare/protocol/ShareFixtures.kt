package cn.limpu.hita.feature.timetableshare.protocol

internal fun fullFixture() = SharedTimetable(
    "11111111-1111-4111-8111-111111111111", "小林", ShareMode.FULL,
    SharedTerm("秋季课表", "2026年秋季", 1788105600000L, 1798646400000L),
    listOf(SharedPeriod(510, 615)), listOf("高等数学"),
    listOf(SharedOccurrence(1788136200000L, 1788142500000L, 0, "A101"))
)

internal const val FULL_JSON = """{"v":1,"id":"11111111-1111-4111-8111-111111111111","n":"小林","m":"full","t":["秋季课表","2026年秋季",1788105600000,1798646400000,"Asia/Shanghai"],"p":[[510,615]],"c":["高等数学"],"e":[[0,1788136200000,1788142500000,"A101"]]}"""
// Independent Python gzip.compress(mtime=0) sample; decoder compatibility must survive encoder changes.
internal const val FIXED_TOKEN = "HITA1:H4sIAAAAAAACCqtWKlOyMtRRykxRslIyhAJdMGECIixgXBhQ0lHKAyp9uqH_2bzpQE4ukJNWmpMDZJYoWUUrPV_e_XTt4hfr971YuAIoZmRgZPZ05xaIqJKOobmFhaGBqZkBCAB5lhZmJmYmEJ6SY3Fmon5wRmJeekZiplKsjlIB0MBoU0MDHTND01ggPxlkwcvVM56v7Xw2dcPTtctAilJBigwgJhubGcFMBvJMjExhJhsaGCrFxtYCAHmQdFXuAAAA"
