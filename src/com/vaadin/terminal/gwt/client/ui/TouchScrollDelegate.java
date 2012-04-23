/*
@VaadinApache2LicenseForJavaFiles@
 */
package com.vaadin.terminal.gwt.client.ui;

import java.util.ArrayList;

import com.google.gwt.animation.client.Animation;
import com.google.gwt.core.client.Duration;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.Node;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.dom.client.Style;
import com.google.gwt.dom.client.Touch;
import com.google.gwt.event.dom.client.ScrollHandler;
import com.google.gwt.event.dom.client.TouchStartEvent;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Event.NativePreviewEvent;
import com.google.gwt.user.client.Event.NativePreviewHandler;
import com.vaadin.terminal.gwt.client.BrowserInfo;
import com.vaadin.terminal.gwt.client.VConsole;

/**
 * Provides one finger touch scrolling for elements with once scrollable
 * elements inside. One widget can have several of these scrollable elements.
 * Scrollable elements are provided in the constructor. Users must pass
 * touchStart events to this delegate, from there on the delegate takes over
 * with an event preview. Other touch events needs to be sunken though.
 * <p>
 * This is bit similar as Scroller class in GWT expenses example, but ideas
 * drawn from iscroll.js project:
 * <ul>
 * <li>uses GWT event mechanism.
 * <li>uses modern CSS trick during scrolling for smoother experience:
 * translate3d and transitions
 * </ul>
 * <p>
 * Scroll event should only happen when the "touch scrolling actually ends".
 * Later we might also tune this so that a scroll event happens if user stalls
 * her finger long enought.
 * 
 * TODO static getter for active touch scroll delegate. Components might need to
 * prevent scrolling in some cases. Consider Table with drag and drop, or drag
 * and drop in scrollable area. Optimal implementation might be to start the
 * drag and drop only if user keeps finger down for a moment, otherwise do the
 * scroll. In this case, the draggable component would need to cancel scrolling
 * in a timer after touchstart event and take over from there.
 * 
 * TODO support scrolling horizontally
 * 
 * TODO cancel if user add second finger to the screen (user expects a gesture).
 * 
 * TODO "scrollbars", see e.g. iscroll.js
 * 
 * TODO write an email to sjobs √§t apple dot com and beg for this feature to be
 * built into webkit. Seriously, we should try to lobbying this to webkit folks.
 * This sure ain't our business to implement this with javascript.
 * 
 * TODO collect all general touch related constant to better place.
 * 
 * @author Matti Tahvonen, Vaadin Ltd
 */
public class TouchScrollDelegate implements NativePreviewHandler {

    private static final double FRICTION = 0.002;
    private static final double DECELERATION = 0.002;
    private static final int MAX_DURATION = 1500;
    private int origY;
    private Element[] scrollableElements;
    private Element scrolledElement;
    private int origScrollTop;
    private HandlerRegistration handlerRegistration;
    private double lastAnimatedTranslateY;
    private int lastClientY;
    private int deltaScrollPos;
    private boolean transitionOn = false;
    private int finalScrollTop;
    private ArrayList<Element> layers;
    private boolean moved;
    private ScrollHandler scrollHandler;

    private static TouchScrollDelegate activeScrollDelegate;

    private static final boolean androidWithBrokenScrollTop = BrowserInfo
            .getBrowserString().contains("Android 3")
            || BrowserInfo.getBrowserString().contains("Android 4");

    public TouchScrollDelegate(Element... elements) {
        scrollableElements = elements;
    }

    public void setScrollHandler(ScrollHandler scrollHandler) {
        this.scrollHandler = scrollHandler;
    }

    public static TouchScrollDelegate getActiveScrollDelegate() {
        return activeScrollDelegate;
    }

    /**
     * Has user moved the touch.
     * 
     * @return
     */
    public boolean isMoved() {
        return moved;
    }

    /**
     * Forces the scroll delegate to cancels scrolling process. Can be called by
     * users if they e.g. decide to handle touch event by themselves after all
     * (e.g. a pause after touch start before moving touch -> interpreted as
     * long touch/click or drag start).
     */
    public void stopScrolling() {
        handlerRegistration.removeHandler();
        handlerRegistration = null;
        if (moved) {
            moveTransformationToScrolloffset();
        } else {
            activeScrollDelegate = null;
        }
    }

    public void onTouchStart(TouchStartEvent event) {
        if (activeScrollDelegate == null && event.getTouches().length() == 1) {
            NativeEvent nativeEvent = event.getNativeEvent();
            doTouchStart(nativeEvent);
        } else {
            /*
             * Touch scroll is currenly on (possibly bouncing). Ignore.
             */
        }
    }

    private void doTouchStart(NativeEvent nativeEvent) {
        if (transitionOn) {
            momentum.cancel();
        }
        Touch touch = nativeEvent.getTouches().get(0);
        if (detectScrolledElement(touch)) {
            VConsole.log("TouchDelegate takes over");
            nativeEvent.stopPropagation();
            handlerRegistration = Event.addNativePreviewHandler(this);
            activeScrollDelegate = this;
            origY = touch.getClientY();
            yPositions[0] = origY;
            eventTimeStamps[0] = getTimeStamp();
            nextEvent = 1;

            origScrollTop = getScrollTop();
            VConsole.log("ST" + origScrollTop);

            moved = false;
            // event.preventDefault();
            // event.stopPropagation();
        }
    }

    private int getScrollTop() {
        if (androidWithBrokenScrollTop) {
            if (scrolledElement.getPropertyJSO("_vScrollTop") != null) {
                return scrolledElement.getPropertyInt("_vScrollTop");
            }
            return 0;
        }
        return scrolledElement.getScrollTop();
    }

    private void onTransitionEnd() {
        if (finalScrollTop < 0) {
            animateToScrollPosition(0, finalScrollTop);
            finalScrollTop = 0;
        } else if (finalScrollTop > getMaxFinalY()) {
            animateToScrollPosition(getMaxFinalY(), finalScrollTop);
            finalScrollTop = getMaxFinalY();
        } else {
            moveTransformationToScrolloffset();
        }
    }

    private void animateToScrollPosition(int to, int from) {
        int dist = Math.abs(to - from);
        int time = getAnimationTimeForDistance(dist);
        if (time <= 0) {
            time = 1; // get animation and transition end event
        }
        VConsole.log("Animate " + time + " " + from + " " + to);
        int translateTo = -to + origScrollTop;
        int fromY = -from + origScrollTop;
        if (androidWithBrokenScrollTop) {
            fromY -= origScrollTop;
            translateTo -= origScrollTop;
        }
        translateTo(time, fromY, translateTo);
    }

    private int getAnimationTimeForDistance(int dist) {
        return 350; // 350ms seems to work quite fine for all distances
        // if (dist < 0) {
        // dist = -dist;
        // }
        // return MAX_DURATION * dist / (scrolledElement.getClientHeight() * 3);
    }

    /**
     * Called at the end of scrolling. Moves possible translate values to
     * scrolltop, causing onscroll event.
     */
    private void moveTransformationToScrolloffset() {
        if (androidWithBrokenScrollTop) {
            scrolledElement.setPropertyInt("_vScrollTop", finalScrollTop);
            if (scrollHandler != null) {
                scrollHandler.onScroll(null);
            }
        } else {
            for (Element el : layers) {
                Style style = el.getStyle();
                style.setProperty("webkitTransform", "translate3d(0,0,0)");
            }
            scrolledElement.setScrollTop(finalScrollTop);
        }
        activeScrollDelegate = null;
        handlerRegistration.removeHandler();
        handlerRegistration = null;
    }

    /**
     * Detects if a touch happens on a predefined element and the element has
     * something to scroll.
     * 
     * @param touch
     * @return
     */
    private boolean detectScrolledElement(Touch touch) {
        Element target = touch.getTarget().cast();
        for (Element el : scrollableElements) {
            if (el.isOrHasChild(target)
                    && el.getScrollHeight() > el.getClientHeight()) {
                scrolledElement = el;
                layers = getElements(scrolledElement);
                return true;

            }
        }
        return false;
    }

    public static ArrayList<Element> getElements(Element scrolledElement2) {
        NodeList<Node> childNodes = scrolledElement2.getChildNodes();
        ArrayList<Element> l = new ArrayList<Element>();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node item = childNodes.getItem(i);
            if (item.getNodeType() == Node.ELEMENT_NODE) {
                l.add((Element) item);
            }
        }
        return l;
    }

    private void onTouchMove(NativeEvent event) {
        if (!moved) {
            double l = (getTimeStamp() - eventTimeStamps[0]);
            VConsole.log(l + " ms from start to move");
        }
        boolean handleMove = readPositionAndSpeed(event);
        if (handleMove) {
            int deltaScrollTop = origY - lastClientY;
            int finalPos = origScrollTop + deltaScrollTop;
            if (finalPos > getMaxFinalY()) {
                // spring effect at the end
                int overscroll = (deltaScrollTop + origScrollTop)
                        - getMaxFinalY();
                overscroll = overscroll / 2;
                if (overscroll > getMaxOverScroll()) {
                    overscroll = getMaxOverScroll();
                }
                deltaScrollTop = getMaxFinalY() + overscroll - origScrollTop;
            } else if (finalPos < 0) {
                // spring effect at the beginning
                int overscroll = finalPos / 2;
                if (-overscroll > getMaxOverScroll()) {
                    overscroll = -getMaxOverScroll();
                }
                deltaScrollTop = overscroll - origScrollTop;
            }
            quickSetScrollPosition(0, deltaScrollTop);
            moved = true;
            event.preventDefault();
            event.stopPropagation();
        }
    }

    private void quickSetScrollPosition(int deltaX, int deltaY) {
        deltaScrollPos = deltaY;
        if (androidWithBrokenScrollTop) {
            deltaY += origScrollTop;
            translateTo(-deltaY);
        } else {
            translateTo(-deltaScrollPos);
        }
    }

    private static final int EVENTS_FOR_SPEED_CALC = 3;
    public static final int SIGNIFICANT_MOVE_THRESHOLD = 3;
    private int[] yPositions = new int[EVENTS_FOR_SPEED_CALC];
    private double[] eventTimeStamps = new double[EVENTS_FOR_SPEED_CALC];
    private int nextEvent = 0;
    private Animation momentum;

    /**
     * 
     * @param event
     * @return
     */
    private boolean readPositionAndSpeed(NativeEvent event) {
        Touch touch = event.getChangedTouches().get(0);
        lastClientY = touch.getClientY();
        int eventIndx = nextEvent++;
        eventIndx = eventIndx % EVENTS_FOR_SPEED_CALC;
        eventTimeStamps[eventIndx] = getTimeStamp();
        yPositions[eventIndx] = lastClientY;
        return isMovedSignificantly();
    }

    private boolean isMovedSignificantly() {
        return moved ? moved
                : Math.abs(origY - lastClientY) >= SIGNIFICANT_MOVE_THRESHOLD;
    }

    private void onTouchEnd(NativeEvent event) {
        if (!moved) {
            activeScrollDelegate = null;
            handlerRegistration.removeHandler();
            handlerRegistration = null;
            return;
        }

        int currentY = origScrollTop + deltaScrollPos;

        int maxFinalY = getMaxFinalY();

        int pixelsToMove;
        int finalY;
        int duration = -1;
        if (currentY > maxFinalY) {
            // we are over the max final pos, animate to end
            pixelsToMove = maxFinalY - currentY;
            finalY = maxFinalY;
        } else if (currentY < 0) {
            // we are below the max final pos, animate to beginning
            pixelsToMove = -currentY;
            finalY = 0;
        } else {
            double pixelsPerMs = calculateSpeed();
            // we are currently within scrollable area, calculate pixels that
            // we'll move due to momentum
            VConsole.log("pxPerMs" + pixelsPerMs);
            pixelsToMove = (int) (0.5 * pixelsPerMs * pixelsPerMs / FRICTION);
            if (pixelsPerMs < 0) {
                pixelsToMove = -pixelsToMove;
            }
            // VConsole.log("pixels to move" + pixelsToMove);

            finalY = currentY + pixelsToMove;

            if (finalY > maxFinalY + getMaxOverScroll()) {
                // VConsole.log("To max overscroll");
                finalY = getMaxFinalY() + getMaxOverScroll();
                int fixedPixelsToMove = finalY - currentY;
                pixelsToMove = fixedPixelsToMove;
            } else if (finalY < 0 - getMaxOverScroll()) {
                // VConsole.log("to min overscroll");
                finalY = -getMaxOverScroll();
                int fixedPixelsToMove = finalY - currentY;
                pixelsToMove = fixedPixelsToMove;
            } else {
                duration = (int) (Math.abs(pixelsPerMs / DECELERATION));
            }
        }
        if (duration == -1) {
            // did not keep in side borders or was outside borders, calculate
            // a good enough duration based on pixelsToBeMoved.
            duration = getAnimationTimeForDistance(pixelsToMove);
        }
        if (duration > MAX_DURATION) {
            VConsole.log("Max animation time. " + duration);
            duration = MAX_DURATION;
        }
        finalScrollTop = finalY;

        if (Math.abs(pixelsToMove) < 3 || duration < 20) {
            VConsole.log("Small 'momentum' " + pixelsToMove + " |  " + duration
                    + " Skipping animation,");
            moveTransformationToScrolloffset();
            return;
        }

        int translateTo = -finalY + origScrollTop;
        int fromY = -currentY + origScrollTop;
        if (androidWithBrokenScrollTop) {
            fromY -= origScrollTop;
            translateTo -= origScrollTop;
        }
        translateTo(duration, fromY, translateTo);
    }

    private double calculateSpeed() {
        if (nextEvent < EVENTS_FOR_SPEED_CALC) {
            VConsole.log("Not enough data for speed calculation");
            // not enough data for decent speed calculation, no momentum :-(
            return 0;
        }
        int idx = nextEvent % EVENTS_FOR_SPEED_CALC;
        final int firstPos = yPositions[idx];
        final double firstTs = eventTimeStamps[idx];
        idx += EVENTS_FOR_SPEED_CALC;
        idx--;
        idx = idx % EVENTS_FOR_SPEED_CALC;
        final int lastPos = yPositions[idx];
        final double lastTs = eventTimeStamps[idx];
        // speed as in change of scrolltop == -speedOfTouchPos
        return (firstPos - lastPos) / (lastTs - firstTs);

    }

    /**
     * Note positive scrolltop moves layer up, positive translate moves layer
     * down.
     */
    private void translateTo(double translateY) {
        for (Element el : layers) {
            Style style = el.getStyle();
            style.setProperty("webkitTransform", "translate3d(0px,"
                    + translateY + "px,0px)");
        }
    }

    /**
     * Note positive scrolltop moves layer up, positive translate moves layer
     * down.
     * 
     * @param duration
     */
    private void translateTo(int duration, final int fromY, final int finalY) {
        if (duration > 0) {
            transitionOn = true;

            momentum = new Animation() {

                @Override
                protected void onUpdate(double progress) {
                    lastAnimatedTranslateY = (fromY + (finalY - fromY)
                            * progress);
                    translateTo(lastAnimatedTranslateY);
                }

                @Override
                protected double interpolate(double progress) {
                    return 1 + Math.pow(progress - 1, 3);
                }

                @Override
                protected void onComplete() {
                    super.onComplete();
                    transitionOn = false;
                    onTransitionEnd();
                }

                @Override
                protected void onCancel() {
                    int delta = (int) (finalY - lastAnimatedTranslateY);
                    finalScrollTop -= delta;
                    moveTransformationToScrolloffset();
                    transitionOn = false;
                }
            };
            momentum.run(duration);
        }
    }

    private int getMaxOverScroll() {
        return androidWithBrokenScrollTop ? 0 : scrolledElement
                .getClientHeight() / 3;
    }

    private int getMaxFinalY() {
        return scrolledElement.getScrollHeight()
                - scrolledElement.getClientHeight();
    }

    public void onPreviewNativeEvent(NativePreviewEvent event) {
        int typeInt = event.getTypeInt();
        if (transitionOn) {
            /*
             * TODO allow starting new events. See issue in onTouchStart
             */
            event.cancel();

            if (typeInt == Event.ONTOUCHSTART) {
                doTouchStart(event.getNativeEvent());
            }
            return;
        }
        switch (typeInt) {
        case Event.ONTOUCHMOVE:
            if (!event.isCanceled()) {
                onTouchMove(event.getNativeEvent());
                if (moved) {
                    event.cancel();
                }
            }
            break;
        case Event.ONTOUCHEND:
        case Event.ONTOUCHCANCEL:
            if (!event.isCanceled()) {
                if (moved) {
                    event.cancel();
                }
                onTouchEnd(event.getNativeEvent());
            }
            break;
        case Event.ONMOUSEMOVE:
            if (moved) {
                // no debug message, mobile safari generates these for some
                // compatibility purposes.
                event.cancel();
            }
            break;
        default:
            VConsole.log("Non touch event:" + event.getNativeEvent().getType());
            event.cancel();
            break;
        }
    }

    public void setElements(com.google.gwt.user.client.Element[] elements) {
        scrollableElements = elements;
    }

    /**
     * long calcucation are not very efficient in GWT, so this helper method
     * returns timestamp in double.
     * 
     * @return
     */
    public static double getTimeStamp() {
        return Duration.currentTimeMillis();
    }

}
