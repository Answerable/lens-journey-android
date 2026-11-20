rootProject.name = "lens-journey-android"

// The root project is the core artifact (ai.varai:lens-journey). The debug attestation provider is a separate, opt-in artifact.
// The two consumer modules are never published: they prove the boundary the way an app sees it.
include(":debug-attestation")      // ai.varai:lens-journey-debug-attestation (staging and test builds only)
include(":boundary-tests")         // an app that depends on the core artifact only
include(":consumer-with-debug")    // an app that also depends on the debug artifact
