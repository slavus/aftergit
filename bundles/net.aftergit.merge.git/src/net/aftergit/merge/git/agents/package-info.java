/**
 * What the resolver and the optional MCP server share: block ids, proposals, the models of open resolvers and the
 * stored proposals. No AI logic lives here; the server bundle registers {@link ProposalSource} only while it runs,
 * the resolver registers {@link OpenModels}.
 */
package net.aftergit.merge.git.agents;
