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
package org.elephant.actions;

import java.net.HttpURLConnection;

import org.elephant.actions.mixins.BdvDataMixin;
import org.elephant.actions.mixins.ElephantConnectException;
import org.elephant.actions.mixins.ElephantGraphActionMixin;
import org.elephant.actions.mixins.ElephantGraphTagActionMixin;
import org.elephant.actions.mixins.ElephantStateManagerMixin;
import org.elephant.actions.mixins.GraphChangeActionMixin;
import org.elephant.actions.mixins.TimepointMixin;
import org.elephant.actions.mixins.UIActionMixin;
import org.elephant.actions.mixins.URLMixin;
import org.elephant.actions.mixins.UnirestMixin;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Spot;
import org.mastodon.model.tag.ObjTagMap;
import org.mastodon.model.tag.TagSetStructure.Tag;
import org.mastodon.ui.keymap.CommandDescriptionProvider;
import org.mastodon.ui.keymap.CommandDescriptions;
import org.mastodon.ui.keymap.KeyConfigContexts;
import org.scijava.plugin.Plugin;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;

import bdv.viewer.animate.TextOverlayAnimator.TextPosition;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;

/**
 * Track the highlighted spot backward, creating a new spot based on a flow
 * estimation.
 * 
 * @author Ko Sugawara
 */
public class BackTrackAction extends AbstractElephantDatasetAction
		implements BdvDataMixin, ElephantGraphActionMixin, ElephantGraphTagActionMixin, ElephantStateManagerMixin, GraphChangeActionMixin, TimepointMixin, UIActionMixin, UnirestMixin, URLMixin
{

	private static final long serialVersionUID = 1L;

	private static final String NAME = "[elephant] back track";

	private static final String[] MENU_KEYS = new String[] { "alt C" };

	private static final String DESCRIPTION = "Track the highlighted vertex backward in time.";

	private int timepoint;

	private JsonObject jsonRootObject;

	private final double[] pos = new double[ 3 ];

	private final double[][] cov = new double[ 3 ][ 3 ];

	private final double[] cov1d = new double[ 9 ];

	/*
	 * Command description.
	 */
	@Plugin( type = Descriptions.class )
	public static class Descriptions extends CommandDescriptionProvider
	{
		public Descriptions()
		{
			super( KeyConfigContexts.BIGDATAVIEWER );
		}

		@Override
		public void getCommandDescriptions( final CommandDescriptions descriptions )
		{
			descriptions.add(
					NAME,
					MENU_KEYS,
					DESCRIPTION );
		}
	}

	@Override
	public String[] getMenuKeys()
	{
		return MENU_KEYS;
	}

	public BackTrackAction()
	{
		super( NAME );
	}

	@Override
	boolean prepare()
	{
		timepoint = getCurrentTimepoint( 0 );
		if ( timepoint < 1 )
			return false;
		final VoxelDimensions voxelSize = getVoxelDimensions();
		final JsonArray scales = new JsonArray()
				.add( voxelSize.dimension( 0 ) )
				.add( voxelSize.dimension( 1 ) )
				.add( voxelSize.dimension( 2 ) );
		final Dimensions dimensions = getRescaledDimensions();
		final JsonArray inputSize = new JsonArray()
				.add( dimensions.dimension( 0 ) )
				.add( dimensions.dimension( 1 ) )
				.add( dimensions.dimension( 2 ) );
		jsonRootObject = Json.object()
				.add( JSON_KEY_DATASET_NAME, getMainSettings().getDatasetName() )
				.add( JSON_KEY_MODEL_NAME, getMainSettings().getFlowModelName() )
				.add( JSON_KEY_DEBUG, getMainSettings().getDebug() )
				.add( JSON_KEY_OUTPUT_PREDICTION, getMainSettings().getOutputPrediction() )
				.add( JSON_KEY_MAX_DISPLACEMENT, getMainSettings().getMaxDisplacement() )
				.add( JSON_KEY_SCALES, scales )
				.add( JSON_KEY_N_KEEP_AXIALS, getNKeepAxials() )
				.add( JSON_KEY_INPUT_SIZE, inputSize )
				.add( JSON_KEY_CACHE_MAXBYTES, getMainSettings().getCacheMaxbytes() )
				.add( JSON_KEY_USE_MEMMAP, getMainSettings().getUseMemmap() );
		if ( getMainSettings().getPatch() )
		{
			jsonRootObject.add( JSON_KEY_PATCH, new JsonArray()
					.add( getMainSettings().getPatchSizeX() )
					.add( getMainSettings().getPatchSizeY() )
					.add( getMainSettings().getPatchSizeZ() ) );
		}
		final Spot spotRef = getGraph().vertexRef();
		getGraph().getLock().readLock().lock();
		try
		{
			final Spot spot = getAppModel().getHighlightModel().getHighlightedVertex( spotRef );
			if ( spot == null )
				return false;
			spot.localize( pos );
			spot.getCovariance( cov );
			for ( int i = 0; i < 3; i++ )
				for ( int j = 0; j < 3; j++ )
					cov1d[ i * 3 + j ] = cov[ i ][ j ];
			final int id = spot.getInternalPoolIndex();
			final JsonObject jsonSpot = Json.object()
					.add( "pos", Json.array( pos ) )
					.add( "covariance", Json.array( cov1d ) )
					.add( "id", id );
			final JsonArray jsonSpots = Json.array().add( jsonSpot );
			jsonRootObject.set( JSON_KEY_TIMEPOINT, timepoint );
			jsonRootObject.set( "spots", jsonSpots );
			final long[] cropOrigin = new long[ 3 ];
			final long[] cropSize = new long[ 3 ];
			calculateCropBoxAround( pos, cropOrigin, cropSize );
			jsonRootObject.add( JSON_KEY_PREDICT_CROP_BOX, Json.array()
					.add( cropOrigin[ 0 ] ).add( cropOrigin[ 1 ] ).add( cropOrigin[ 2 ] )
					.add( cropSize[ 0 ] ).add( cropSize[ 1 ] ).add( cropSize[ 2 ] ) );
		}
		finally
		{
			getGraph().getLock().readLock().unlock();
			getGraph().releaseRef( spotRef );
		}
		return true;
	}

	@Override
	public void processDataset()
	{
		try
		{
			postAsStringAsync( getEndpointURL( ENDPOINT_FLOW_PREDICT ), jsonRootObject.toString(),
					response -> {
						if ( response.getStatus() == HttpURLConnection.HTTP_OK )
						{
							final JsonObject rootObject = Json.parse( response.getBody() ).asObject();
							final JsonArray jsonSpots = rootObject.get( "spots" ).asArray();
							final JsonObject jsonSpot = jsonSpots.get( 0 ).asObject();
							final int spotId = jsonSpot.get( "id" ).asInt();
							final Spot orgSpotRef = getGraph().vertexRef();
							final Spot newSpotRef = getGraph().vertexRef();
							final Link edgeRef = getGraph().edgeRef();
							getGraph().getLock().writeLock().lock();
							try
							{
								final Spot spot = getGraph().vertices().stream().filter( s -> s.getInternalPoolIndex() == spotId ).findFirst().orElse( null );
								if ( spot == null )
								{
									final String msg = "spot " + spot + " was not found";
									getClientLogger().info( msg );
									showTextOverlayAnimator( msg, 3000, TextPosition.CENTER );
								}
								else
								{
									orgSpotRef.refTo( spot );
									final JsonArray jsonPositions = jsonSpot.get( "pos" ).asArray();
									for ( int j = 0; j < 3; j++ )
										pos[ j ] = jsonPositions.get( j ).asDouble();
									final Spot newSpot = getGraph().addVertex( newSpotRef ).init( timepoint - 1, pos, cov );
									final Tag detectionFNTag = getTag( getDetectionTagSet(), DETECTION_FN_TAG_NAME );
									final Tag trackingApprovedTag = getTag( getTrackingTagSet(), TRACKING_APPROVED_TAG_NAME );
									getVertexTagMap( getDetectionTagSet() ).set( newSpot, detectionFNTag );
									final ObjTagMap< Spot, Tag > tagMapTrackingSpot = getVertexTagMap( getTrackingTagSet() );
									tagMapTrackingSpot.set( newSpot, trackingApprovedTag );
									final Link edge = getGraph().addEdge( newSpot, spot, edgeRef ).init();
									final ObjTagMap< Link, Tag > tagMapTrackingLink = getEdgeTagMap( getTrackingTagSet() );
									tagMapTrackingLink.set( edge, trackingApprovedTag );
								}
							}
							finally
							{
								getModel().setUndoPoint();
								getGraph().getLock().writeLock().unlock();
								getGraph().getLock().readLock().lock();
								try
								{
									getGroupHandle().getModel( getAppModel().NAVIGATION ).notifyNavigateToVertex( newSpotRef );
								}
								finally
								{
									getGraph().getLock().readLock().unlock();
								}
								notifyGraphChanged();
								getGraph().releaseRef( orgSpotRef );
								getGraph().releaseRef( newSpotRef );
								getGraph().releaseRef( edgeRef );
							}
						}
						else
						{
							final StringBuilder sb = new StringBuilder( response.getStatusText() );
							if ( response.getStatus() == HttpURLConnection.HTTP_INTERNAL_ERROR )
							{
								sb.append( ": " );
								sb.append( Json.parse( response.getBody() ).asObject().get( "error" ).asString() );
							}
							showTextOverlayAnimator( sb.toString(), 3000, TextPosition.CENTER );
							getClientLogger().severe( sb.toString() );
						}
					} );
		}
		catch ( final ElephantConnectException e )
		{
			// already handled by UnirestMixin
		}
	}

}
