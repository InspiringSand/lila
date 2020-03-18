package lila.bot

import scala.concurrent.duration._
import scala.concurrent.Promise

import chess.format.Uci
import lila.common.Bus
import lila.game.Game.PlayerId
import lila.game.{ Game, GameRepo, Pov }
import lila.hub.actorApi.map.Tell
import lila.hub.actorApi.round.{ Abort, BotPlay, RematchNo, RematchYes, Resign }
import lila.round.actorApi.round.{ DrawNo, DrawYes }
import lila.user.User

final class BotPlayer(
    chatApi: lila.chat.ChatApi,
    gameRepo: GameRepo,
    isOfferingRematch: lila.round.IsOfferingRematch
)(implicit ec: scala.concurrent.ExecutionContext, system: akka.actor.ActorSystem) {

  private def clientError[A](msg: String): Fu[A] = fufail(lila.round.ClientError(msg))

  def apply(pov: Pov, me: User, uciStr: String, offeringDraw: Option[Boolean]): Funit =
    lila.common.Future.delay((pov.game.hasAi ?? 500) millis) {
      Uci(uciStr).fold(clientError[Unit](s"Invalid UCI: $uciStr")) { uci =>
        lila.mon.bot.moves(me.username).increment()
        if (!pov.isMyTurn) clientError("Not your turn, or game already over")
        else {
          val promise = Promise[Unit]
          if (pov.player.isOfferingDraw && (offeringDraw contains false)) declineDraw(pov)
          else if (!pov.player.isOfferingDraw && ~offeringDraw) offerDraw(pov)
          Bus.publish(
            Tell(pov.gameId, BotPlay(pov.playerId, uci, promise.some)),
            "roundMapTell"
          )
          promise.future
        }
      }
    }

  def chat(gameId: Game.ID, me: User, d: BotForm.ChatData) = fuccess {
    lila.mon.bot.chats(me.username).increment()
    val chatId = lila.chat.Chat.Id {
      if (d.room == "player") gameId else s"$gameId/w"
    }
    val source = d.room == "spectator" option {
      lila.hub.actorApi.shutup.PublicSource.Watcher(gameId)
    }
    chatApi.userChat.write(chatId, me.id, d.text, publicSource = source)
  }

  def rematchAccept(id: Game.ID, me: User): Fu[Boolean] = rematch(id, me, true)

  def rematchDecline(id: Game.ID, me: User): Fu[Boolean] = rematch(id, me, false)

  private def rematch(id: Game.ID, me: User, accept: Boolean): Fu[Boolean] =
    gameRepo game id map {
      _.flatMap(Pov(_, me)).filter(p => isOfferingRematch(!p)) ?? { pov =>
        // delay so it feels more natural
        lila.common.Future.delay(if (accept) 100.millis else 2.seconds) {
          fuccess {
            Bus.publish(
              Tell(pov.gameId, (if (accept) RematchYes else RematchNo)(pov.playerId)),
              "roundMapTell"
            )
          }
        }
        true
      }
    }

  def abort(pov: Pov): Funit =
    if (!pov.game.abortable) clientError("This game can no longer be aborted")
    else
      fuccess {
        Bus.publish(
          Tell(pov.gameId, Abort(pov.playerId)),
          "roundMapTell"
        )
      }

  def resign(pov: Pov): Funit =
    if (pov.game.abortable) abort(pov)
    else if (pov.game.resignable) fuccess {
      Bus.publish(
        Tell(pov.gameId, Resign(pov.playerId)),
        "roundMapTell"
      )
    } else clientError("This game cannot be resigned")

  def declineDraw(pov: Pov): Unit =
    if (pov.game.drawable && pov.opponent.isOfferingDraw)
      Bus.publish(
        Tell(pov.gameId, DrawNo(PlayerId(pov.playerId))),
        "roundMapTell"
      )

  def offerDraw(pov: Pov): Unit =
    if (pov.game.drawable && pov.game.playerCanOfferDraw(pov.color))
      Bus.publish(
        Tell(pov.gameId, DrawYes(PlayerId(pov.playerId))),
        "roundMapTell"
      )

  def setDraw(pov: Pov, v: Boolean): Unit =
    if (v) offerDraw(pov) else declineDraw(pov)
}
