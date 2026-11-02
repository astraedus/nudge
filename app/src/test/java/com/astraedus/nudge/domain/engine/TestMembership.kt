package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.groups.GroupDefinition
import com.astraedus.nudge.domain.groups.GroupMembershipResolver

/** A resolver whose groups hold only hand-added apps: `membership(10L to "com.a", 10L to "com.b")`. */
internal fun membership(vararg pairs: Pair<Long, String>): GroupMembershipResolver =
    GroupMembershipResolver(
        pairs.groupBy({ it.first }, { it.second }).map { (id, pkgs) -> GroupDefinition(id, includes = pkgs.toSet()) }
    )
