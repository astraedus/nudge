package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.repository.BlockRuleRepository
import javax.inject.Inject

class AddBlockRuleUseCase @Inject constructor(
    private val blockRuleRepository: BlockRuleRepository
) {

    suspend fun invoke(rule: BlockRule): Long = blockRuleRepository.addRule(rule)
}
