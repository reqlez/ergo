package org.ergoplatform.utils

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import org.ergoplatform.modifiers.history.extension.Extension
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.modifiers.{ErgoFullBlock, BlockSection}
import org.ergoplatform.nodeView.history.ErgoHistory
import org.ergoplatform.nodeView.state.{ErgoState, StateType, UtxoState}
import org.ergoplatform.settings.Algos
import org.ergoplatform.nodeView.ErgoNodeViewHolder.CurrentView
import org.ergoplatform.nodeView.ErgoNodeViewHolder.ReceivableMessages.{GetDataFromCurrentView, LocallyGeneratedModifier}
import org.ergoplatform.network.ErgoNodeViewSynchronizer.ReceivableMessages._
import scorex.core.validation.MalformedModifierError
import scorex.util.ModifierId

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

trait NodeViewBaseOps extends ErgoTestHelpers {

  type Ctx = NodeViewTestContext
  type CurView = CurrentView[ErgoState[_]]
  implicit private val timeout: Timeout = defaultTimeout

  def getCurrentView(implicit ctx: Ctx): CurView = {
    val request = GetDataFromCurrentView[ErgoState[_], CurView](view => view)
    await((nodeViewHolderRef ? request).mapTo[CurView])
  }

  def getHistory(implicit ctx: Ctx): ErgoHistory = getCurrentView.history

  def getCurrentState(implicit ctx: Ctx): ErgoState[_] = getCurrentView.state

  def verifyTransactions(implicit ctx: Ctx): Boolean = ctx.settings.nodeSettings.verifyTransactions

  def stateType(implicit ctx: Ctx): StateType = ctx.settings.nodeSettings.stateType

  def applyHeader(header: Header)(implicit ctx: Ctx): Try[Unit] = {
    subscribeModificationOutcome()
    nodeViewHolderRef ! LocallyGeneratedModifier(header)
    expectModificationOutcome(header)
  }

  def applyBlock(fullBlock: ErgoFullBlock, excludeExt: Boolean = false)(implicit ctx: Ctx): Try[Unit] = {
    subscribeModificationOutcome()
    nodeViewHolderRef ! LocallyGeneratedModifier(fullBlock.header)
    expectModificationOutcome(fullBlock.header).flatMap(_ => applyPayload(fullBlock, excludeExt))
  }

  def applyPayload(fullBlock: ErgoFullBlock, excludeExt: Boolean = false)(implicit ctx: Ctx): Try[Unit] = {
    subscribeModificationOutcome()
    val sections = if (verifyTransactions && excludeExt) {
      fullBlock.blockSections.filterNot(_.modifierTypeId == Extension.modifierTypeId)
    } else if (verifyTransactions) {
      fullBlock.blockSections
    } else {
      Seq.empty
    }
    sections.foldLeft(Success(()): Try[Unit]) { (lastResult, section) =>
      lastResult.flatMap { _ =>
        nodeViewHolderRef ! LocallyGeneratedModifier(section)
        section match {
          case Extension(_, Seq(), _) => Success(()) // doesn't send back any outcome
          case _ => expectModificationOutcome(section) // normal flow
        }
      }
    }
  }

  def subscribeModificationOutcome()(implicit ctx: Ctx): Unit = {
    subscribeEvents(classOf[SyntacticallySuccessfulModifier])
    subscribeEvents(classOf[SyntacticallyFailedModification])
  }

  def expectModificationOutcome(section: BlockSection)(implicit ctx: Ctx): Try[Unit] = {
    expectMsgType[ModificationOutcome] match {
      case SyntacticallySuccessfulModifier(_, modId) if modId == section.id =>
        Success(())
      case outcome =>
        val msg = section match {
          case header: Header => s"Error applying header ${header.id}: $outcome"
          case other => s"Error applying section $other: $outcome"
        }
        val e = new MalformedModifierError(msg, section.id, section.modifierTypeId)
        log.error(msg, e)
        Failure(e)
    }
  }

  /** Creates next block in chain from transactions, works only for UTXO configurations
    */
  def makeNextBlock(utxoState: UtxoState,
                    txs: Seq[ErgoTransaction])
                   (implicit ctx: Ctx): ErgoFullBlock = {
    val time = timeProvider.time()
    val parent = getHistory.bestFullBlockOpt
    validFullBlock(parent, utxoState, txs, Some(time))
  }

  @inline private def nodeViewHolderRef(implicit ctx: Ctx): ActorRef = ctx.nodeViewHolderRef

  @inline def send(msg: Any)(implicit ctx: Ctx): Unit = ctx.testProbe.send(nodeViewHolderRef, msg)

  @inline def ctxTimeout(implicit ctx: Ctx): FiniteDuration = ctx.testProbe.remainingOrDefault

  @inline def expectMsg[T](obj: T)(implicit ctx: Ctx): T = ctx.testProbe.expectMsg(obj)

  @inline def expectMsgType[T](implicit ctx: Ctx, t: ClassTag[T]): T = ctx.testProbe.expectMsgType

  @inline def expectNoMsg()(implicit ctx: Ctx): Unit = ctx.testProbe.expectNoMessage(ctxTimeout)

  @inline def ignoreMsg(f: PartialFunction[Any, Boolean])(implicit ctx: Ctx): Unit = ctx.testProbe.ignoreMsg(f)

  @inline def ignoreNoMsg()(implicit ctx: Ctx): Unit = ctx.testProbe.ignoreNoMsg()

  @inline def subscribeEvents(eventType: Class[_])(implicit ctx: Ctx): Boolean = {
    ctx.actorSystem.eventStream.subscribe(ctx.testProbe.ref, eventType)
  }

  @inline def unsubscribeEvents(eventType: Class[_])(implicit ctx: Ctx): Boolean = {
    ctx.actorSystem.eventStream.unsubscribe(ctx.testProbe.ref, eventType)
  }
}

trait NodeViewTestOps extends NodeViewBaseOps {

  def getBestHeaderOpt(implicit ctx: Ctx): Option[Header] = getHistory.bestHeaderOpt

  def getPoolSize(implicit ctx: Ctx): Int = getCurrentView.pool.size

  def getRootHash(implicit ctx: Ctx): String = Algos.encode(getCurrentState.rootHash)

  def getBestFullBlockOpt(implicit ctx: Ctx): Option[ErgoFullBlock] = getHistory.bestFullBlockOpt

  def getBestFullBlockEncodedId(implicit ctx: Ctx): Option[String] = getBestFullBlockOpt.map(_.header.encodedId)

  def getBestHeaderEncodedId(implicit ctx: Ctx): Option[String] = getBestHeaderOpt.map(_.encodedId)


  def getHistoryHeight(implicit ctx: Ctx): Int = getHistory.headersHeight

  def getHeightOf(id: scorex.util.ModifierId)(implicit ctx: Ctx): Option[Int] = getHistory.heightOf(id)

  def getLastHeadersLength(count: Int)(implicit ctx: Ctx): Int = getHistory.lastHeaders(count).size

  def getModifierById(id: ModifierId)(implicit ctx: Ctx): Option[BlockSection] = getHistory.modifierById(id)

  def getGenesisStateDigest(implicit ctx: Ctx): Array[Byte] =
    ctx.settings.chainSettings.genesisStateDigest

}

object NodeViewTestOps extends NodeViewTestOps
