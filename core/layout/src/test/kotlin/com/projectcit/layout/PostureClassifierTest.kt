package com.projectcit.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class PostureClassifierTest {
    @Test fun candybarIsPortrait() = assertEquals(Posture.PORTRAIT, PostureClassifier.classify(Devices.candybar))
    @Test fun sidewaysIsLandscape() = assertEquals(Posture.LANDSCAPE, PostureClassifier.classify(Devices.candybarLandscape))
    @Test fun coverIsPortrait() = assertEquals(Posture.PORTRAIT, PostureClassifier.classify(Devices.foldCover))
    @Test fun innerFlatIsBookFlat() =
        assertEquals(Posture.BOOK_FLAT, PostureClassifier.classify(Devices.foldInner(HingeState.FLAT)))
    @Test fun innerHalfIsBookHalf() =
        assertEquals(Posture.BOOK_HALF_OPENED, PostureClassifier.classify(Devices.foldInner(HingeState.HALF_OPENED)))
    @Test fun flexModeIsTabletop() = assertEquals(Posture.TABLETOP, PostureClassifier.classify(Devices.foldTabletop))
    @Test fun flatHorizontalSeamIsPlainLandscapeWhenOptedOut() =
        assertEquals(Posture.LANDSCAPE, PostureClassifier.classify(Devices.foldFlatLandscape, flatHorizontalAsTabletop = false))

    @Test fun flatHorizontalSeamCanUseFlexLayout() =
        assertEquals(Posture.TABLETOP, PostureClassifier.classify(Devices.foldFlatLandscape, flatHorizontalAsTabletop = true))

    @Test fun flatVerticalSeamIsNeverClamshell() =
        assertEquals(Posture.BOOK_FLAT, PostureClassifier.classify(Devices.foldInner(HingeState.FLAT), flatHorizontalAsTabletop = true))

    @Test fun foldInnerIsMediumNotExpanded() {
        // The reason posture is keyed off the hinge, not the size class.
        assertEquals(SizeClass.MEDIUM, Devices.foldInner(HingeState.FLAT).widthClass)
    }

    @Test fun hingeOutsideWindowIsIgnored() {
        val w = Devices.candybar.copy(
            hinge = HingeInfo(Rect(0f, 0f, 0f, 2400f), HingeOrientation.VERTICAL, HingeState.FLAT, false, false),
        )
        assertEquals(Posture.PORTRAIT, PostureClassifier.classify(w))
    }
}
