import io

p = 'app/src/main/java/com/lumenbook/reader/data/LibraryRepository.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace("""import kotlinx.coroutines.Dispatchers""", """import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope""", 1)
s = s.replace("""import kotlinx.coroutines.withContext""", """import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext""", 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
