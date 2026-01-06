package green.mobileapps.clippervideocutter

import android.app.Application
import android.util.Log
import green.mobileapps.clippervideocutter.UtilEngine

class ZApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("ZApplication", "application onCreate")
        UtilEngine.init(this)
    }
}