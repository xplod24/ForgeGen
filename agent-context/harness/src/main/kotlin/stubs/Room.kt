package androidx.room

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS) annotation class Entity(val tableName: String = "", val indices: Array<Index> = [])
annotation class Index(vararg val value: String)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER) annotation class PrimaryKey(val autoGenerate: Boolean = false)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER) annotation class ColumnInfo(val name: String = "", val defaultValue: String = "")
@Target(AnnotationTarget.CLASS) annotation class Dao
@Target(AnnotationTarget.FUNCTION) annotation class Query(val value: String)
@Target(AnnotationTarget.FUNCTION) annotation class Insert(val onConflict: Int = OnConflictStrategy.ABORT)
@Target(AnnotationTarget.FUNCTION) annotation class Delete
@Target(AnnotationTarget.FUNCTION) annotation class Update(val entity: KClass<*> = Any::class, val onConflict: Int = OnConflictStrategy.ABORT)
@Target(AnnotationTarget.CLASS) annotation class Database(val entities: Array<KClass<*>>, val version: Int, val exportSchema: Boolean = true)

annotation class OnConflictStrategy {
    companion object {
        const val REPLACE = 1
        const val ABORT = 3
        const val IGNORE = 5
    }
}

abstract class RoomDatabase

object Room {
    /** Harness hook: tests install an in-memory database implementation here. */
    @JvmStatic var testFactory: ((Class<*>) -> Any)? = null

    @JvmStatic fun <T : RoomDatabase> databaseBuilder(context: android.content.Context, klass: Class<T>, name: String?) = Builder(klass)

    class Builder<T : RoomDatabase>(private val klass: Class<T>) {
        fun addMigrations(vararg migrations: androidx.room.migration.Migration) = this
        fun fallbackToDestructiveMigration(dropAllTables: Boolean) = this
        @Suppress("UNCHECKED_CAST")
        fun build(): T = (testFactory ?: error("Room.testFactory not set"))(klass) as T
    }
}
