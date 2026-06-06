package com.wangchaozhi.wechatassistant.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * Generates a Baseline Profile for the production application.
 *
 * Run on a connected device or CI emulator:
 * ./gradlew :app:generateBaselineProfile -PwcaBaselineProfileCi=true
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.wangchaozhi.wechatassistant",
    ) {
        pressHome()
        startActivityAndWait()
    }
}
