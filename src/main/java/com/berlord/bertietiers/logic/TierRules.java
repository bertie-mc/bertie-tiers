package com.berlord.bertietiers.logic;

/**
 * The decision order, and nothing else. Every hook in this mod funnels into
 * {@link com.berlord.bertietiers.logic.MiningAuthority}, which funnels into this method, so the
 * rules exist in exactly one place:
 *
 * <ol>
 *   <li>a point {@code tool -> block} exception always wins;</li>
 *   <li>otherwise, if both sides are in tiers, mining is allowed when
 *       {@code tool level >= block level};</li>
 *   <li>otherwise, if the block is controlled but the tool is in no tier, mining is refused;</li>
 *   <li>otherwise the block is not in the system at all and Bertie does not interfere.</li>
 * </ol>
 *
 * Pure integers on purpose: this is the part that gets unit tested directly.
 */
public final class TierRules {
    private TierRules() {}

    /**
     * @param blockLevel      tier level of the block, or null when the block is not configured
     * @param exceptionAllows true when a point exception names this exact tool and block
     * @param toolLevel       tier level of the tool, or null when the tool is in no tier
     */
    public static Decision decide(Integer blockLevel, boolean exceptionAllows, Integer toolLevel) {
        if (blockLevel == null) {
            return Decision.PASS; // rule 4: not ours, keep the original behaviour
        }
        if (exceptionAllows) {
            return Decision.ALLOW; // rule 1: the point exception beats the numbers
        }
        if (toolLevel == null) {
            return Decision.DENY; // rule 3: controlled block, unassigned tool
        }
        return toolLevel >= blockLevel ? Decision.ALLOW : Decision.DENY; // rule 2
    }
}
