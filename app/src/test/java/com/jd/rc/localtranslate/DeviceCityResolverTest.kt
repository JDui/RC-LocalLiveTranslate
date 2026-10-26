package com.jd.rc.localtranslate

import android.location.Address
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.jd.rc.localtranslate.data.DeviceCityResolver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceCityResolverTest {
    @Test fun usesCityThenDistrictButDoesNotMistakeProvinceOrCountryForCity() {
        val address = Address(Locale.CHINA).apply { locality = "上海市"; subAdminArea = "市辖区"; adminArea = "上海市" }
        assertEquals("上海市", DeviceCityResolver.cityFromAddress(address))
        address.locality = "黄浦区"
        assertEquals("上海市", DeviceCityResolver.cityFromAddress(address))
        address.adminArea = "京都府"
        address.locality = " [東京]\n "
        assertEquals("東京", DeviceCityResolver.cityFromAddress(address))
        address.locality = null; address.subAdminArea = "京都市"
        assertEquals("京都市", DeviceCityResolver.cityFromAddress(address))
        address.subAdminArea = null; address.countryName = "日本"
        assertNull(DeviceCityResolver.cityFromAddress(address))
    }
    @Test fun rejectsStaleMissingAndFutureCachedFixes() {
        val now = 2_000_000_000_000L
        val fix = Location("network").apply { elapsedRealtimeNanos = now - 1_000_000_000L }
        assertTrue(DeviceCityResolver.isRecent(fix, now))
        fix.elapsedRealtimeNanos = now - 601_000_000_000L
        assertFalse(DeviceCityResolver.isRecent(fix, now))
        fix.elapsedRealtimeNanos = now + 1
        assertFalse(DeviceCityResolver.isRecent(fix, now))
        fix.elapsedRealtimeNanos = 0
        assertFalse(DeviceCityResolver.isRecent(fix, now))
    }
    @Test fun lateDeviceResultCannotOverwriteAChangedManualCity() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        app.settings.update(com.jd.rc.localtranslate.data.SettingsChange(sessionCity = "原城市"))
        app.settings.update(com.jd.rc.localtranslate.data.SettingsChange(sessionCity = "手动城市"))
        assertFalse(app.settings.updateCityIfUnchanged("原城市", "定位城市"))
        assertEquals("手动城市", app.settings.settings.first().sessionCity)
        assertTrue(app.settings.updateCityIfUnchanged("手动城市", " [定位城市] "))
        assertEquals("定位城市", app.settings.settings.first().sessionCity)
    }
    @Test fun deniedPermissionReturnsWithoutChangingStoredCity() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TranslateApplication>()
        app.settings.update(com.jd.rc.localtranslate.data.SettingsChange(sessionCity = "手动城市"))
        assertNull(DeviceCityResolver(app).resolve())
        assertEquals("手动城市", app.settings.settings.first().sessionCity)
    }
}
