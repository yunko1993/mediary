package app.mediary.data

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 仅供已授权 USB 调试的开发机配置自己的手机；不编入 App APK，也不接受 Intent/URL 口令。 */
class McpDeviceSetupTest {
    @Test fun configureFromPrivateCache() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("configureMcp") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "mcp-setup.json")
        try {
            require(file.length() in 1..8192) { "Private device configuration file is missing or oversized" }
            val config = JSONObject(file.readText())
            ConnectionSettings(context).saveMcp(config.getString("endpoint"), config.getString("token"))
        } finally { file.delete() }
    }
}
