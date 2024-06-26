package kotlinx.coroutines

import java.security.Permission

@Suppress("unused")
class TestSecurityManager : SecurityManager() {
    override fun checkPropertyAccess(key: String?) {
        if (key?.startsWith("kotlinx.") == true && key != "kotlinx.coroutines.parallelism.compensation")
            throw SecurityException("'$key' property is not allowed")
    }

    override fun checkPermission(perm: Permission?) {
        /* allow everything else */
    }
}