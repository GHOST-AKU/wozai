package dev.ghost.nearbyim.next
import dev.ghost.nearbyim.mesh.MeshMessage
import org.junit.Assert.*
import org.junit.Test
class MeshDraftPolicyTest {
    private fun message(id:String,state:String="queued",own:Boolean=true)=MeshMessage(id,"same body","a".repeat(64),0,own,state,1)
    @Test fun oldIdenticalBodyCannotAcknowledgeNewSend(){
        assertFalse(acceptedMeshSend(setOf("old"),"same body",listOf(message("old"))))
    }
    @Test fun acknowledgementSurvivesStateFlowConflation(){
        for(state in listOf("queued","handed","expired","stopped_unconfirmed"))
            assertTrue(acceptedMeshSend(setOf("old"),"same body",listOf(message("new",state))))
    }
    @Test fun incomingOrOtherBodyCannotClearDraft(){
        assertFalse(acceptedMeshSend(emptySet(),"same body",listOf(message("new","received",false))))
        assertFalse(acceptedMeshSend(emptySet(),"edited body",listOf(message("new"))))
    }
}
