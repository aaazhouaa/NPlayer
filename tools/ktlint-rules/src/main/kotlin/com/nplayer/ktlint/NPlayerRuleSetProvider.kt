package com.nplayer.ktlint

import com.pinterest.ktlint.cli.ruleset.core.api.RuleSetProviderV3
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import com.pinterest.ktlint.rule.engine.core.api.RuleSetId

class NPlayerRuleSetProvider : RuleSetProviderV3(id = RuleSetId(value = "nplayer")) {
    override fun getRuleProviders(): Set<RuleProvider> = setOf(RuleProvider { CallLayoutRule() })
}
