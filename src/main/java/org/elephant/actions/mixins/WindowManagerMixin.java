/*******************************************************************************
 * Copyright (C) 2020, Ko Sugawara
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package org.elephant.actions.mixins;

import java.util.List;
import java.util.function.Consumer;

import org.mastodon.mamut.MamutViewBdv;
import org.mastodon.mamut.WindowManager;
import org.mastodon.mamut.WindowManager.BdvViewCreatedListener;
import org.mastodon.views.bdv.ViewerFrameMamut;

import bdv.viewer.animate.TextOverlayAnimator;
import bdv.viewer.animate.TextOverlayAnimator.TextPosition;

/**
 * Handle @{@link WindowManager} and {@link MamutViewBdv}.
 * 
 * @author Ko Sugawara
 */
public interface WindowManagerMixin extends ElephantActionMixin
{

	default WindowManager getWindowManager()
	{
		return getPluginAppModel().getWindowManager();
	}

	default List< MamutViewBdv > getBdvWindows()
	{
		return getWindowManager().getBdvWindows();
	}

	default void forEachBdvView( final Consumer< ? super MamutViewBdv > action )
	{
		getBdvWindows().forEach( action );
	}

	default void addBdvCreatedListener( final BdvViewCreatedListener listener )
	{
		getWindowManager().bdvViewCreatedListeners().add( listener );
	}

	default void removeBdvCreatedListener( final BdvViewCreatedListener listener )
	{
		getWindowManager().bdvViewCreatedListeners().remove( listener );
	}

	default void addTextOverlayAnimator( final String text, final long duration, final TextPosition position )
	{
		final TextOverlayAnimator overlayAnimator = new TextOverlayAnimator( text, 3000, TextPosition.CENTER );
		forEachBdvView( bdv -> ( ( ViewerFrameMamut ) bdv.getFrame() ).getViewerPanel().addOverlayAnimator( overlayAnimator ) );
	}

}
