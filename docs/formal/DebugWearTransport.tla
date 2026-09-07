------------------------------ MODULE DebugWearTransport ------------------------------
CONSTANTS DebugBuild, ReleaseBuild, PairingGrant, AuthenticatedDataLayer

ASSUME DebugBuild # ReleaseBuild

DebugEvidence(build, grant, authenticated) ==
    build = DebugBuild /\\ grant = FALSE /\\ authenticated = FALSE

DebugTransportAllowed(build, grant, authenticated) ==
    DebugEvidence(build, grant, authenticated)

NoProductionPairingEvidence ==
    \\A build, grant, authenticated :
        DebugTransportAllowed(build, grant, authenticated) =>
            build = DebugBuild /\\ grant = FALSE /\\ authenticated = FALSE

=============================================================================
