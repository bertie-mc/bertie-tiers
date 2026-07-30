package com.berlord.bertietiers.logic;

/** What Bertie says about one (tool, block) pair. */
public enum Decision {
    /** Bertie controls this block and grants the drops. Overrides any contrary check. */
    ALLOW,
    /** Bertie controls this block and refuses the drops. Overrides any contrary check. */
    DENY,
    /** Bertie does not control this block; keep whatever vanilla or another mod decided. */
    PASS
}
