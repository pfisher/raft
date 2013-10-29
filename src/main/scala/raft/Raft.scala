package raft 

import scala.language.postfixOps
import akka.actor.{ Actor, ActorRef, FSM }
import scala.concurrent.duration._

/* types */
object Raft {
  type Term = Int
  type NodeId = Int
}

case class LogEntry(entry: String, term: Raft.Term)

/* messages */
sealed trait Message
case class RequestVote(term: Raft.Term, candidateId: Raft.NodeId,
    lastLogIndex: Int, lastLogTerm: Raft.Term) extends Message
case object Timeout extends Message

sealed trait Vote
case class DenyVote(term: Raft.Term) extends Vote
case class GrantVote(term: Raft.Term) extends Vote

/* states */
sealed trait Role
case object Leader extends Role 
case object Follower extends Role
case object Candidate extends Role

/* state data */
case class Data(currentTerm: Raft.Term, votedFor: Option[Raft.NodeId],
    log: List[LogEntry], commitIndex: Int, lastApplied: Int)

/* Consensus module */
class Raft() extends Actor with FSM[Role, Data] {
  startWith(Follower, Data(0, None, List(), 0, 0))
  
  when(Follower) {
    case Event(rpc: RequestVote, data: Data) =>
      vote(rpc, data) match {
        case (msg: GrantVote, updData) =>
          cancelTimer("timeout")
          setTimer("timeout", Timeout, 200 millis, false) // reset timer
          stay using(updData) replying(msg) 
        case (msg: DenyVote, updData) =>
        	stay using(updData) replying(msg) // continue without resetting timer
      }
    case Event(Timeout, data) =>
      goto(Candidate) using data
  }
  
  when(Candidate) {
    case Event(_, _) =>
      stay
  }
  
  whenUnhandled {
    case Event(s, d) =>
      stay
  }
  
  initialize()
  
  /*
   * Determine whether to grant or deny vote
   */
  private def vote(rpc: RequestVote, data: Data): (Vote, Data) =
	  if (alreadyVoted(data)) deny(data)
	  else if (rpc.term < data.currentTerm) deny(data)
	  else if (rpc.term == data.currentTerm)
	    if (candidateLogTermIsBehind(rpc, data)) deny(data)
	    else if (candidateLogTermIsEqualButShorterLog(rpc, data)) deny(data)
	    else grant(rpc, data) // follower and candidate are equal, grant
	  else grant(rpc, data) // candidate is ahead, grant
  
  private def deny(d: Data) = (DenyVote(d.currentTerm), d)
  private def grant(rpc: RequestVote, d: Data): (Vote, Data) = {
    val newdata = d.copy(votedFor = Some(rpc.candidateId))
	  (GrantVote(newdata.currentTerm), newdata)
  }
  
  private def candidateLogTermIsBehind(rpc: RequestVote, data: Data) = 
    data.log.last.term > rpc.lastLogTerm
  
  private def candidateLogTermIsEqualButShorterLog(rpc: RequestVote, data: Data) =
    (data.log.last.term == rpc.lastLogTerm) && 
	  (data.log.length-1 > rpc.lastLogIndex)
  
  private def alreadyVoted(data: Data): Boolean = data.votedFor match {
    case Some(_) => true
    case None => false
  }
}
