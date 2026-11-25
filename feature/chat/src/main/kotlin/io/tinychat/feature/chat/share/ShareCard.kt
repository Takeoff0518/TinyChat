package io.tinychat.feature.chat.share

import androidx.compose.ui.res.stringResource
import io.tinychat.feature.chat.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tinychat.designsystem.TcLogoMark
import io.tinychat.markdown.TcMarkdown

/**
 * 分享长图的内容卡片（M8-2）。
 *
 * 只放**这一条回答的正文** + 页脚（02 §5.7/§12.9）。刻意不加：
 * - 头像、模型名、时间戳（官方分享图也没有，加了反而像截图）；
 * - DeepSeek 标识（品牌规则）；只留 TinyChat 字样。
 *
 * 背景强制白色：这份 Composable 只会被 [ShareCapture] 以浅色主题渲染。
 */
@Composable
fun ShareCard(
    text: String,
    width: Dp,
) {
    Column(
        modifier = Modifier
            .width(width)
            .background(Color.White)
            .padding(horizontal = 26.dp, vertical = 30.dp),
    ) {
        TcMarkdown(
            content = text,
            streaming = false,
        )

        Spacer(Modifier.height(26.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0x14000000)),
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chat_ai_generated_content_check_important_information),
                fontSize = 12.sp,
                color = Color(0xFF81858C),
                modifier = Modifier.weight(1f),
            )
            // 页脚标识 = 圆角方块 + 字样（决策 62）；白纸上不需要描边
            TcLogoMark(size = 14.dp, ring = false)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "TinyChat",
                fontSize = 12.sp,
                color = Color(0xFF81858C),
            )
        }
    }
}
