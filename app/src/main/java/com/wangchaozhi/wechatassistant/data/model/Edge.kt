package com.wangchaozhi.wechatassistant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 节点图的一条有向连线：fromActionId 的 fromPort 出口 → toActionId。
 * 不对 from/toActionId 加外键（避免删除顺序耦合）；边由 scriptId CASCADE 清理，
 * 悬空边在加载/遍历时按缺失节点过滤。
 */
@Entity(
    tableName = "edges",
    foreignKeys = [
        ForeignKey(
            entity = Script::class,
            parentColumns = ["id"],
            childColumns = ["scriptId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("scriptId"), Index("fromActionId"), Index("toActionId")],
)
data class Edge(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scriptId: Long,
    val fromActionId: Long,
    val toActionId: Long,
    // 普通节点恒 0；IF_PAGE_CHANGED 节点 0=变了(true) / 1=没变(false)。
    val fromPort: Int = 0,
)
