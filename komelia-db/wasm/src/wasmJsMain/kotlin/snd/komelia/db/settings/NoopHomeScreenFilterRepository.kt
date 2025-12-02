package snd.komelia.db.settings

import io.github.snd_r.komelia.ui.home.HomeScreenFilter
import io.github.snd_r.komelia.ui.home.HomeScreenFilterRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class NoopHomeScreenFilterRepository : HomeScreenFilterRepository {
    private val filters = MutableStateFlow<List<HomeScreenFilter>>(emptyList())
    
    override fun getFilters(): Flow<List<HomeScreenFilter>> {
        return filters
    }
    
    override suspend fun putFilters(filters: List<HomeScreenFilter>) {
        this.filters.value = filters
    }
}
