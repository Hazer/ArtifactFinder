/*
 * Copyright 2019 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.birbit.artifactfinder.model.db

import com.birbit.artifactfinder.model.*
import com.birbit.artifactfinder.vo.Artifactory
import com.google.common.truth.Truth.assertThat
import java.sql.SQLException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class ArtifactDaoImplTest {
    private val db = ArtifactFinderDb(null)
    private val dao = db.artifactDao
    private val scope = TestCoroutineScope()

    @Test
    fun insertArtifact() = scope.runBlockingTest {
        assertThat(
            dao.findArtifact(
                groupId = "",
                artifactId = "",
                version = ARTIFACT.version
            )
        ).isNull()
        assertThat(dao.insertArtifact(ARTIFACT)).isEqualTo(1L)
        assertThat(
            dao.findArtifact(
                groupId = ARTIFACT.groupId,
                artifactId = ARTIFACT.artifactId,
                version = ARTIFACT.version
            )
        ).isEqualTo(ARTIFACT.copy(id = 1))
        assertThat(
            dao.findArtifact(
                groupId = "not_true",
                artifactId = ARTIFACT.artifactId,
                version = ARTIFACT.version
            )
        ).isNull()
    }

    @Test
    fun pendingArtifact() = scope.runBlockingTest {
        assertThat(
            dao.findNextPendingArtifact(emptyList())
        ).isNull()

        dao.insertPendingArtifact(PENDING)
        val pending = PENDING.copy(id = 1)

        assertThat(
            dao.findNextPendingArtifact(emptyList())
        ).isEqualTo(
            pending
        )

        assertThat(
            dao.findNextPendingArtifact(listOf(3))
        ).isEqualTo(
            pending
        )
        assertThat(
            dao.findNextPendingArtifact(listOf(3, 5))
        ).isEqualTo(
            pending
        )

        assertThat(
            dao.findNextPendingArtifact(listOf(1, 3, 5))
        ).isNull()

        assertThat(
            dao.findNextPendingArtifact(listOf(1))
        ).isNull()

        dao.incrementPendingArtifactRetry(pending.id)

        assertThat(
            dao.findNextPendingArtifact(emptyList())
        ).isEqualTo(
            pending.copy(
                retries = pending.retries + 1
            )
        )

        dao.markPendingArtifactFetched(1)
        assertThat(
            dao.findNextPendingArtifact(emptyList())
        ).isNull()
    }

    @Test
    fun insertClassRecord_withoutArtifact() = scope.runBlockingTest {
        try {
            dao.insertClassRecord(CLASS_RECORD)
            throw AssertionError("class record w/o artifact should fail")
        } catch (sqlException: SQLException) {
            // ignored
        }
        assertThat(allClassRecords()).isEmpty()
    }

    @Test
    fun insertClassRecord() = scope.runBlockingTest {
        val classRecordId = db.withTransaction {
            val artifactId = dao.insertArtifact(ARTIFACT)
            dao.insertClassRecord(
                CLASS_RECORD.copy(
                    artifactId = artifactId
                )
            )
        }

        assertThat(allClassRecords()).containsExactly(
            CLASS_RECORD.copy(
                id = classRecordId
            )
        )

        // try to re-insert, nothing should happen
        dao.insertClassRecord(
            CLASS_RECORD.copy(
                id = classRecordId
            )
        )
        assertThat(allClassRecords()).containsExactly(
            CLASS_RECORD.copy(
                id = classRecordId
            )
        )
    }

    private suspend fun allClassRecords() = db.query("SELECT * FROM ClassRecord") {
        it.asSequence().map {
            ClassRecord(
                id = it.requireLong(ArtifactDaoImpl.ID),
                pkg = it.requireString(ArtifactDaoImpl.PKG),
                name = it.requireString(ArtifactDaoImpl.NAME),
                artifactId = it.requireLong(ArtifactDaoImpl.ARTIFACT_ID)
            )
        }.toList()
    }

    @Test
    fun insertClassLookupWithoutClass() = scope.runBlockingTest {
        try {
            dao.insertClassLookup(CLASS_LOOKUP)
            throw AssertionError("class lookup without class should fail")
        } catch (_: SQLException) {
            // ignored
        }
    }

    @Test
    fun insertClassLookup() = scope.runBlockingTest {
        val artifactId = dao.insertArtifact(ARTIFACT)
        val classId = dao.insertClassRecord(
            CLASS_RECORD.copy(
                artifactId = artifactId
            )
        )
        val classLookup = CLASS_LOOKUP.copy(
            classId = classId
        )
        dao.insertClassLookup(classLookup)
        assertThat(dao.allLookups()).containsExactly(classLookup)
        dao.insertClassLookup(classLookup)
        assertThat(dao.allLookups()).containsExactly(classLookup)
    }

    @Test
    fun deletingArtifactDeletesRelated() = scope.runBlockingTest {
        val artifactId = dao.insertArtifact(ARTIFACT)
        val artifact = ARTIFACT.copy(
            id = artifactId
        )
        val classId = dao.insertClassRecord(
            CLASS_RECORD.copy(
                artifactId = artifact.id
            )
        )
        val classLookup = CLASS_LOOKUP.copy(
            classId = classId
        )
        dao.insertClassLookup(classLookup)
        dao.deleteArtifact(artifact)
        assertThat(dao.allLookups()).isEmpty()
        assertThat(allClassRecords()).isEmpty()
    }

    @Test
    fun deletingClassDeletesLookup() = scope.runBlockingTest {
        val artifactId = dao.insertArtifact(ARTIFACT)
        val artifact = ARTIFACT.copy(
            id = artifactId
        )
        val classId = dao.insertClassRecord(
            CLASS_RECORD.copy(
                artifactId = artifact.id
            )
        )
        val classLookup = CLASS_LOOKUP.copy(
            classId = classId
        )
        dao.insertClassLookup(classLookup)
        dao.deleteClassRecord(
            CLASS_RECORD.copy(
                id = classId,
                artifactId = artifact.id
            )
        )
        assertThat(dao.allLookups()).isEmpty()
        assertThat(allClassRecords()).isEmpty()
    }

    @Test
    fun checkVersion() = scope.runBlockingTest {
        assertThat(db.query("PRAGMA user_version") {
            it.nextRow()
            it.requireInt("user_version")
        }).isEqualTo(2)
    }

    companion object {
        val ARTIFACT = Artifact(
            id = 0,
            groupId = "foo",
            artifactId = "bar",
            version = Version.fromString("2.2.0")!!,
            artifactory = Artifactory.MAVEN
        )
        val PENDING = PendingArtifact(
            id = 0,
            groupId = ARTIFACT.groupId,
            artifactId = ARTIFACT.artifactId,
            version = ARTIFACT.version,
            retries = 3,
            fetched = false,
            artifactory = Artifactory.MAVEN
        )
        val CLASS_LOOKUP = ClassLookup(
            identifier = "foo.bar",
            classId = 10
        )
        val CLASS_RECORD = ClassRecord(
            id = 0,
            pkg = "foo.bar",
            name = "Baz",
            artifactId = 1
        )
    }
}
