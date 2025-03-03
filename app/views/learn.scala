package views.html.learn

import play.api.libs.json.Json

import lila.api.Context
import lila.app.templating.Environment._
import lila.app.ui.ScalatagsTemplate._
import lila.common.String.html.safeJsonValue

import controllers.routes
import lila.common.LangPath

object index {

  import trans.learn.{ play => _, _ }

  def apply(data: Option[play.api.libs.json.JsValue])(implicit ctx: Context) =
    views.html.base.layout(
      title = s"${learnChess.txt()} - ${byPlaying.txt()}",
      moreJs = frag(
        jsModule("learn"),
        embedJsUnsafeLoadThen(s"""LichessLearn(document.getElementById('learn-app'), ${safeJsonValue(
            Json.obj(
              "data" -> data,
              "i18n" -> i18nJsObject(i18nKeys)
            )
          )})""")
      ),
      moreCss = cssTag("learn"),
      openGraph = lila.app.ui
        .OpenGraph(
          title = "Learn chess by playing",
          description = "You don't know much about chess? Excellent! Let's have fun and learn to play chess!",
          url = s"$netBaseUrl${routes.Learn.index}"
        )
        .some,
      zoomable = true,
      chessground = false,
      withHrefLangs = LangPath(routes.Learn.index).some
    ) {
      main(id := "learn-app")
    }

  private val i18nKeys: List[lila.i18n.MessageKey] =
    List(
      learnChess,
      byPlaying,
      menu,
      progressX,
      resetMyProgress,
      youWillLoseAllYourProgress,
      trans.learn.play,
      chessPieces,
      theRook,
      itMovesInStraightLines,
      rookIntro,
      rookGoal,
      grabAllTheStars,
      theFewerMoves,
      useTwoRooks,
      rookComplete,
      theBishop,
      itMovesDiagonally,
      bishopIntro,
      youNeedBothBishops,
      bishopComplete,
      theQueen,
      queenCombinesRookAndBishop,
      queenIntro,
      queenComplete,
      theKing,
      theMostImportantPiece,
      kingIntro,
      theKingIsSlow,
      lastOne,
      kingComplete,
      theKnight,
      itMovesInAnLShape,
      knightIntro,
      knightsHaveAFancyWay,
      knightsCanJumpOverObstacles,
      knightComplete,
      thePawn,
      itMovesForwardOnly,
      pawnIntro,
      pawnsMoveOneSquareOnly,
      mostOfTheTimePromotingToAQueenIsBest,
      pawnsMoveForward,
      captureThenPromote,
      useAllThePawns,
      aPawnOnTheSecondRank,
      grabAllTheStarsNoNeedToPromote,
      pawnComplete,
      pawnPromotion,
      yourPawnReachedTheEndOfTheBoard,
      itNowPromotesToAStrongerPiece,
      selectThePieceYouWant,
      fundamentals,
      capture,
      takeTheEnemyPieces,
      captureIntro,
      takeTheBlackPieces,
      takeTheBlackPiecesAndDontLoseYours,
      captureComplete,
      protection,
      keepYourPiecesSafe,
      protectionIntro,
      protectionComplete,
      escape,
      noEscape,
      dontLetThemTakeAnyUndefendedPiece,
      combat,
      captureAndDefendPieces,
      combatIntro,
      combatComplete,
      checkInOne,
      attackTheOpponentsKing,
      checkInOneIntro,
      checkInOneGoal,
      checkInOneComplete,
      outOfCheck,
      defendYourKing,
      outOfCheckIntro,
      escapeWithTheKing,
      theKingCannotEscapeButBlock,
      youCanGetOutOfCheckByTaking,
      thisKnightIsCheckingThroughYourDefenses,
      escapeOrBlock,
      outOfCheckComplete,
      mateInOne,
      defeatTheOpponentsKing,
      mateInOneIntro,
      attackYourOpponentsKing,
      mateInOneComplete,
      intermediate,
      boardSetup,
      howTheGameStarts,
      boardSetupIntro,
      thisIsTheInitialPosition,
      firstPlaceTheRooks,
      thenPlaceTheKnights,
      placeTheBishops,
      placeTheQueen,
      placeTheKing,
      pawnsFormTheFrontLine,
      boardSetupComplete,
      castling,
      theSpecialKingMove,
      castlingIntro,
      castleKingSide,
      castleQueenSide,
      theKnightIsInTheWay,
      castleKingSideMovePiecesFirst,
      castleQueenSideMovePiecesFirst,
      youCannotCastleIfMoved,
      youCannotCastleIfAttacked,
      findAWayToCastleKingSide,
      findAWayToCastleQueenSide,
      castlingComplete,
      enPassant,
      theSpecialPawnMove,
      enPassantIntro,
      blackJustMovedThePawnByTwoSquares,
      enPassantOnlyWorksImmediately,
      enPassantOnlyWorksOnFifthRank,
      takeAllThePawnsEnPassant,
      enPassantComplete,
      stalemate,
      theGameIsADraw,
      stalemateIntro,
      stalemateGoal,
      stalemateComplete,
      advanced,
      pieceValue,
      evaluatePieceStrength,
      pieceValueIntro,
      queenOverBishop,
      takeThePieceWithTheHighestValue,
      pieceValueLegal,
      pieceValueExchange,
      pieceValueComplete,
      checkInTwo,
      twoMovesToGiveCheck,
      checkInTwoIntro,
      checkInTwoGoal,
      checkInTwoComplete,
      whatNext,
      youKnowHowToPlayChess,
      register,
      getAFreeLichessAccount,
      practice,
      learnCommonChessPositions,
      puzzles,
      exerciseYourTacticalSkills,
      videos,
      watchInstructiveChessVideos,
      playPeople,
      opponentsFromAroundTheWorld,
      playMachine,
      testYourSkillsWithTheComputer,
      letsGo,
      stageX,
      awesome,
      excellent,
      greatJob,
      perfect,
      outstanding,
      wayToGo,
      yesYesYes,
      youreGoodAtThis,
      nailedIt,
      rightOn,
      stageXComplete,
      trans.yourScore,
      next,
      nextX,
      backToMenu,
      puzzleFailed,
      retry
    ).map(_.key)
}
