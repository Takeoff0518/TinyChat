package io.tinychat

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import io.tinychat.crash.CrashGuard
import io.tinychat.crash.LocalWipe

/**
 * 进程入口（M9-2）。
 *
 * 只做两件事，顺序不能换：
 * 1. 先执行待清理（崩溃页上的「全部删除」只留了标记，真正的删除放在这里、在任何
 *    Room / DataStore 打开之前）；
 * 2. 再装崩溃兜底，保证之后任何线程崩掉都走我们自己的页面。
 */
class TinyChatApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        LocalWipe.performIfRequested(this)
        CrashGuard.install(this)
    }
}
