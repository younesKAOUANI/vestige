package dev.youneskaouani.vestige.matching;

/** One stage of the matching pipeline. */
interface MatchPass {

    /** The strategy this pass records on the occurrences it produces. */
    MatchStrategy strategy();

    /** Claims whatever pairs this pass can prove, leaving everything else for later passes. */
    void run(MatchingWorkspace workspace);
}
