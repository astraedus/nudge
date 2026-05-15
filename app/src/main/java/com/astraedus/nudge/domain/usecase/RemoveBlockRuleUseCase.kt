package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.repository.BlockRuleRepository
import javax.inject.Inject

class RemoveBlockRuleUseCase @Inject constructor(
    private val blockRuleRepository: BlockRuleRepository
) {

    suspend fun invoke(ruleId: Long) = blockRuleRepository.deleteRule(ruleId)
}
