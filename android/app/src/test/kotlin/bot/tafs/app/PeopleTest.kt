package bot.tafs.app

import bot.tafs.app.app.AppModel
import bot.tafs.app.kiff.Group
import bot.tafs.app.kiff.GroupMember
import bot.tafs.app.kiff.InMemorySecretStore
import bot.tafs.app.kiff.KiffClient
import bot.tafs.app.kiff.Person
import bot.tafs.app.kiff.Share
import bot.tafs.app.model.PeopleWords
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** People and groups, and sharing a taf: KIFF keeps it all; each change is one confirmation, then everything is read again. */
class PeopleTest {
  private val transport = FakeTransport()
  private val secrets = InMemorySecretStore()
  private val auth = FakeAuthenticator()
  private val signer = softwareSigner(auth)
  private val t0 = Instant.parse("2026-10-28T09:00:00Z")

  private val groups = """{"groups":[
    {"id":"g_sup","name":"support","members":[{"subject":"u_ana","role":"member"},{"subject":"u_maria","role":"manager"}],"agents":["refunds-1"]},
    {"id":"g_all","name":"Everyone","everyone":true,"members":[{"subject":"u_me","role":"member"}]}]}"""
  private val people = """{"members":[
    {"subject":"u_maria","role":"editor","status":"active","email":"maria@acme.com"},
    {"subject":"u_gone","role":"viewer","status":"removed","email":"gone@acme.com"},
    {"subject":"u_ana","role":"viewer","status":"active","email":"ana@acme.com"},
    {"subject":"u_me","role":"owner","status":"active","email":"me@acme.com","you":true}]}"""

  private suspend fun model(capabilities: String = """"members.manage""""): AppModel {
    transport.answer("GET /v1/me", 200, """{"tenant_id":"t_01","tenant_slug":"acme","capabilities":[$capabilities]}""")
    transport.answer("GET /v1/home", 200, """{"tafs":[{"id":"taf_1","name":"Refunds","kiff_agent":"refunds-1","color":1,"state":"idle","card":{"ids":["c1"],"active":true}}],"others":[],"removing":[]}""")
    transport.answer("GET /v1/me/card-exceptions", 200, """{"exceptions":[]}""")
    transport.answer("GET /v1/me/activity", 200, """{"calls":[]}""")
    transport.answer("GET /v1/me/access", 200, """{"agents":{"refunds-1":{"use":true,"oversee":true,"manage":true}}}""")
    transport.answer("GET /v1/groups", 200, groups)
    transport.answer("GET /v1/tenant/members", 200, people)
    secrets.save("at_1", AppModel.TOKEN)
    secrets.save("t_01", AppModel.TENANT)
    secrets.save("1", AppModel.KEYED)
    secrets.save(KiffClient.fingerprint(signer.approvalPublicKeyDER), AppModel.APPROVAL_KEYED)
    return testModel(auth = auth, transport = transport, secrets = secrets, signer = signer, clock = { t0 }, start = false).also { it.restore(); it.refresh() }
  }

  @Test fun everyoneComesFirstThenByNameAndOnlyActivePeopleShow() = runTest {
    val m = model()
    m.refreshPeople()
    val s = m.state.value
    assertEquals(listOf("Everyone", "support"), s.groups.map { it.name })
    assertEquals(listOf("ana@acme.com", "maria@acme.com", "me@acme.com"), s.people.map { it.label })
    assertEquals("u_me", s.me?.subject)
  }

  @Test fun aGroupsManagerManagesItsPeopleWithoutMembersManage() = runTest {
    val m = model(capabilities = """"holds.answer"""")
    m.refreshPeople()
    val s = m.state.value
    assertFalse(s.canManagePeople)
    assertFalse(s.managesGroup(s.groups.first { it.id == "g_sup" }))
    val managed = s.copy(groups = s.groups.map { g -> if (g.id == "g_sup") g.copy(members = g.members + GroupMember("u_me", "manager")) else g })
    assertTrue(managed.managesGroup(managed.groups.first { it.id == "g_sup" }))
  }

  @Test fun aChangeIsSignedBehindOneConfirmationThenReadAgain() = runTest {
    transport.answer("POST /v1/groups", 201, """{"id":"g_fin","name":"Finance"}""")
    val m = model()
    m.refreshPeople()
    val reads = transport.requestsTo("GET /v1/groups").size
    assertTrue(m.changePeople("Create the group Finance") { it.createGroup("Finance") })
    assertEquals(listOf("Create the group Finance"), auth.asked)
    assertNotNull(transport.requestsTo("POST /v1/groups").single().headers["KIFF-Device-Signature"])
    assertEquals(reads + 1, transport.requestsTo("GET /v1/groups").size)
  }

  @Test fun backingOutChangesNothing() = runTest {
    val m = model()
    auth.confirms = false
    assertFalse(m.changePeople("Delete the group support") { it.deleteGroup("g_sup") })
    assertTrue(transport.requestsTo("DELETE /v1/groups/g_sup").isEmpty())
  }

  @Test fun aRefusalSaysWhatDidntGoThrough() = runTest {
    transport.answer("PUT /v1/groups/g_sup/members/u_ana", 403, """{"error":"forbidden"}""")
    val m = model()
    assertFalse(m.changePeople("Make ana@acme.com a manager of support") { it.setGroupMember("g_sup", "u_ana", "manager") })
    assertTrue(m.state.value.peopleError!!.startsWith("Make ana@acme.com a manager of support didn't go through"))
  }

  @Test fun sharingAddsAndRemovesUnderOneConfirmation() = runTest {
    transport.answer("POST /v1/agents/refunds-1/grants", 201)
    transport.answer("DELETE /v1/agents/refunds-1/grants", 204, "")
    val m = model()
    assertTrue(m.setShares("refunds-1", "Refunds", setOf(Share(group = "g_sup")), setOf(Share(subject = "u_ana"))))
    assertEquals(listOf("Share Refunds"), auth.asked)
    assertEquals("""{"group":"g_sup"}""", String(transport.requestsTo("POST /v1/agents/refunds-1/grants").single().body!!))
    val removal = transport.requestsTo("DELETE /v1/agents/refunds-1/grants").single()
    assertEquals("subject=u_ana", URI(removal.url).rawQuery)
    assertNotNull(removal.headers["KIFF-Device-Signature"])
  }

  // MARK: Words

  private val maria = Person("u_maria", status = "active", email = "maria@acme.com")
  private val ana = Person("u_ana", status = "active", email = "ana@acme.com")
  private val support = Group("g_sup", "Support", members = listOf(GroupMember("u_ana", "member"), GroupMember("u_maria", "manager")), agents = listOf("refunds-1", "gone"))

  @Test fun theWordsSayWhoUsesWhat() {
    val names = mapOf("refunds-1" to "Refunds")
    assertEquals("Uses Refunds", PeopleWords.uses(support) { names[it] })
    assertEquals("Uses no taf yet", PeopleWords.uses(support.copy(agents = emptyList())) { null })
    assertEquals("Everyone on the account · uses no taf", PeopleWords.uses(Group("g", "Everyone", everyone = true)) { null })
    assertEquals("maria@acme.com (manager), ana@acme.com", PeopleWords.peopleOf(support, listOf(maria, ana)))
    assertEquals("Sharing…", PeopleWords.sharedWith(null, listOf(support), listOf(maria, ana)))
    assertEquals("Not shared", PeopleWords.sharedWith(emptyList(), listOf(support), listOf(maria, ana)))
    assertEquals("Shared with Support and ana@acme.com", PeopleWords.sharedWith(listOf(Share(group = "g_sup"), Share(subject = "u_ana")), listOf(support), listOf(maria, ana)))
  }

  @Test fun theConsequencesSayWhoGetsItAndWhoStops() {
    val lines = PeopleWords.consequences("Refunds", had = setOf(Share(subject = "u_ana")), chosen = setOf(Share(group = "g_sup")), groups = listOf(support), people = listOf(maria, ana))
    assertEquals(
      listOf(
        "Each person gets their own chats and notes. No one else sees them in the app.",
        "Support uses Refunds: maria@acme.com (manager), ana@acme.com.",
        "ana@acme.com stops using Refunds. Their chats stay theirs.",
      ),
      lines,
    )
  }
}
