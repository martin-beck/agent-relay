# Scripted runtime harness

Provider tests can use the provider test-fixtures module without production
dependencies. ScriptedRemoteAgentRuntime matches each RemoteCommand exactly,
returns bounded delayed stdout and stderr frames, and records a command ledger.
Its duplex process enforces expected writes, terminal exit state, and complete
consumption.

Keep fixtures synthetic. Do not place credentials, prompts, private paths,
transcripts, or host addresses in commands or frames. Call assertComplete after
each scenario so missing and leftover interactions fail deterministically.
