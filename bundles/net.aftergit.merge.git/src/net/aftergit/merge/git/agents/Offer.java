package net.aftergit.merge.git.agents;

/** What an open resolver did with a proposal (agents design §3). */
public enum Offer {
  /** Applied as one undo step: the block was an untouched open conflict (or already held the text). */
  APPLIED,
  /** Kept waiting for the user: the block was edited or resolved before. */
  PENDING,
  /** The open file has no such conflict (any more). */
  NOT_FOUND
}
