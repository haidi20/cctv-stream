package id.warung.monitor.feature.onboarding.data

/** JVM fake; stores records in memory in call order. */
class InMemoryOnboardingStore(
    private var record: OnboardingRecord? = null,
) : OnboardingStore {

    val saved = mutableListOf<OnboardingRecord>()

    override suspend fun load(): OnboardingRecord? = record

    override suspend fun save(record: OnboardingRecord) {
        this.record = record
        saved += record
    }
}