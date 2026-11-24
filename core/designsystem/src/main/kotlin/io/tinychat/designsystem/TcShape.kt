package io.tinychat.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 形状令牌（docs/02 §10.2）。 */
val BubbleShape = RoundedCornerShape(22.dp)
val InputShape = RoundedCornerShape(24.dp)
val CardShape = RoundedCornerShape(12.dp)
val DialogShape = RoundedCornerShape(24.dp)
val PillShape = RoundedCornerShape(50)

val TcShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = CardShape,
    large = RoundedCornerShape(16.dp),
    extraLarge = DialogShape,
)
