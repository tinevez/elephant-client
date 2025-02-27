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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.elephant.actions.mixins.BdvDataMixin;
import org.elephant.actions.mixins.ElephantConnectException;
import org.elephant.actions.mixins.ElephantConstantsMixin;
import org.elephant.actions.mixins.ElephantGraphActionMixin;
import org.elephant.actions.mixins.ElephantGraphTagActionMixin;
import org.elephant.actions.mixins.ElephantSettingsMixin;
import org.elephant.actions.mixins.ElephantUtils;
import org.elephant.actions.mixins.SpatioTemporalIndexActionMinxin;
import org.elephant.actions.mixins.TimepointMixin;
import org.elephant.actions.mixins.UIActionMixin;
import org.elephant.actions.mixins.URLMixin;
import org.mastodon.collection.RefCollections;
import org.mastodon.collection.RefList;
import org.mastodon.kdtree.IncrementalNearestNeighborSearch;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Spot;
import org.mastodon.model.tag.ObjTagMap;
import org.mastodon.model.tag.TagSetStructure.Tag;
import org.mastodon.model.tag.TagSetStructure.TagSet;
import org.mastodon.spatial.SpatialIndex;
import org.mastodon.ui.keymap.CommandDescriptionProvider;
import org.mastodon.ui.keymap.CommandDescriptions;
import org.mastodon.ui.keymap.KeyConfigContexts;
import org.scijava.plugin.Plugin;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import bdv.viewer.animate.TextOverlayAnimator.TextPosition;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.RealPoint;

/**
 * A linking workflow based on Nearest Neighbor with/without a flow estimation.
 * 
 * @author Ko Sugawara
 */
public class NearestNeighborLinkingAction extends AbstractElephantDatasetAction
		implements BdvDataMixin, ElephantConstantsMixin, ElephantGraphActionMixin, ElephantSettingsMixin, ElephantGraphTagActionMixin, UIActionMixin, SpatioTemporalIndexActionMinxin, TimepointMixin, URLMixin
{

	private static final long serialVersionUID = 1L;

	private static final String NAME_BASE = "[elephant] nearest neighbor linking%s";

	private static final String NAME_ENTIRE = String.format( NAME_BASE, "" );

	private static final String NAME_AROUND_MOUSE = String.format( NAME_BASE, " (around mouse)" );

	private static final String MENU_TEXT = "Nearest Neighbor Linking";

	private static final String[] MENU_KEYS_ENTIRE = new String[] { "alt L" };

	private static final String[] MENU_KEYS_AROUND_MOUSE = new String[] { "alt shift L" };

	private static final String DESCRIPTION_BASE = "Link spots by the nearest neighbor algorithm. %s";

	private static final String DESCRIPTION_ENTIRE = String.format( DESCRIPTION_BASE, "(entire view)" );

	private static final String DESCRIPTION_AROUND_MOUSE = String.format( DESCRIPTION_BASE, "(around mouse)" );

	public enum NearestNeighborLinkingActionMode
	{
		ENTIRE( NAME_ENTIRE, MENU_KEYS_ENTIRE ),
		AROUND_MOUSE( NAME_AROUND_MOUSE, MENU_KEYS_AROUND_MOUSE );

		private String name;

		private String[] menuKeys;

		private NearestNeighborLinkingActionMode( final String name, final String[] menuKeys )
		{
			this.name = name;
			this.menuKeys = menuKeys;
		}

		public String getName()
		{
			return name;
		}

		public String[] getMenuKeys()
		{
			return menuKeys;
		}
	}

	private final NearestNeighborLinkingActionMode mode;

	private final BdvViewMouseMotionService mouseMotionService;

	private VoxelDimensions cropBoxOrigin;

	private VoxelDimensions cropBoxSize;

	private List< Tag > tagsToProcess;

	private JsonObject jsonRootObject;

	private double distanceThreshold;

	private double squaredDistanceThreshold;

	private int maxEdges;

	private final double[] pos = new double[ 3 ];

	private final double[][] cov = new double[ 3 ][ 3 ];

	private Iterator< Integer > timepointIterator;

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
					NAME_ENTIRE,
					MENU_KEYS_ENTIRE,
					DESCRIPTION_ENTIRE );
			descriptions.add(
					NAME_AROUND_MOUSE,
					MENU_KEYS_AROUND_MOUSE,
					DESCRIPTION_AROUND_MOUSE );
		}
	}

	@Override
	public String getMenuText()
	{
		return MENU_TEXT;
	}

	@Override
	public String[] getMenuKeys()
	{
		return mode.getMenuKeys();
	}

	public NearestNeighborLinkingAction( final NearestNeighborLinkingActionMode mode, final BdvViewMouseMotionService mouseMotionService )
	{
		super( mode.getName() );
		this.mode = mode;
		this.mouseMotionService = mouseMotionService;
	}

	@Override
	boolean prepare()
	{
		final int timepointEnd = getCurrentTimepoint( 0 );
		final int timeRange = getActionStateManager().isLivemode() ? 1 : getMainSettings().getTimeRange();
		final int timepointStart = Math.max( 1, timepointEnd - timeRange + 1 );
		timepointIterator = IntStream.rangeClosed( timepointStart, timepointEnd )
				.boxed().sorted( Collections.reverseOrder() ).iterator();

		getActionStateManager().setAborted( false );

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
				.add( JSON_KEY_CACHE_MAXBYTES, getMainSettings().getCacheMaxbytes() )
				.add( JSON_KEY_IS_3D, !is2D() )
				.add( JSON_KEY_USE_MEMMAP, getMainSettings().getUseMemmap() )
				.add( JSON_KEY_BATCH_SIZE, getMainSettings().getBatchSize() )
				.add( JSON_KEY_INPUT_SIZE, inputSize );
		if ( getMainSettings().getPatch() )
		{
			jsonRootObject.add( JSON_KEY_PATCH, new JsonArray()
					.add( getMainSettings().getPatchSizeX() )
					.add( getMainSettings().getPatchSizeY() )
					.add( getMainSettings().getPatchSizeZ() ) );
		}

		if ( mode == NearestNeighborLinkingActionMode.AROUND_MOUSE )
		{
			final long[] cropOrigin = new long[ 3 ];
			final long[] cropSize = new long[ 3 ];
			mouseMotionService.getMousePositionGlobal( pos );
			calculateCropBoxAround( pos, cropOrigin, cropSize );
			jsonRootObject.add( JSON_KEY_PREDICT_CROP_BOX, Json.array()
					.add( cropOrigin[ 0 ] ).add( cropOrigin[ 1 ] ).add( cropOrigin[ 2 ] )
					.add( cropSize[ 0 ] ).add( cropSize[ 1 ] ).add( cropSize[ 2 ] ) );
			cropBoxOrigin = new FinalVoxelDimensions(
					getVoxelDimensions().unit(),
					cropOrigin[ 0 ] * voxelSize.dimension( 0 ),
					cropOrigin[ 1 ] * voxelSize.dimension( 1 ),
					cropOrigin[ 2 ] * voxelSize.dimension( 2 ) );
			cropBoxSize = new FinalVoxelDimensions(
					getVoxelDimensions().unit(),
					cropSize[ 0 ] * voxelSize.dimension( 0 ),
					cropSize[ 1 ] * voxelSize.dimension( 1 ),
					cropSize[ 2 ] * voxelSize.dimension( 2 ) );
		}
		tagsToProcess = Arrays.asList(
				getTag( getDetectionTagSet(), DETECTION_TP_TAG_NAME ),
				getTag( getDetectionTagSet(), DETECTION_FN_TAG_NAME ),
				getTag( getDetectionTagSet(), DETECTION_UNLABELED_TAG_NAME ) );
		distanceThreshold = getMainSettings().getNNLinkingThreshold();
		squaredDistanceThreshold = distanceThreshold * distanceThreshold;
		maxEdges = getMainSettings().getNNMaxEdges();
		return true;
	}

	@Override
	public void processDataset()
	{
		processNext( timepointIterator, pos, cov );
	}

	private void processNext( final Iterator< Integer > timepointIterator, final double[] pos, final double[][] cov )
	{
		if ( timepointIterator.hasNext() )
		{
			final int timepoint = timepointIterator.next();

			final Tag trackingUnlabeledTag = getTag( getTrackingTagSet(), TRACKING_UNLABELED_TAG_NAME );
			Predicate< Link > edgeFilter = edge -> edge.getTarget().getTimepoint() == timepoint;
			edgeFilter = edgeFilter.and( edge -> getEdgeTagMap( getTrackingTagSet() ).get( edge ) == trackingUnlabeledTag );
			if ( mode == NearestNeighborLinkingActionMode.AROUND_MOUSE )
				edgeFilter = edgeFilter.and( edge -> ElephantUtils.edgeIsInside( edge, cropBoxOrigin, cropBoxSize ) );
			// acquire lock inside removeEdgesTaggedWith
			removeEdges( getGraph().edges(), edgeFilter );

			final JsonArray jsonSpots = Json.array();
			getGraph().getLock().readLock().lock();
			try
			{
				final TagSet tagSetDetection = getDetectionTagSet();
				final ObjTagMap< Spot, Tag > tagMapDetection = getVertexTagMap( tagSetDetection );
				Predicate< Spot > spotFilter = spot -> spot.getTimepoint() == timepoint;
				spotFilter = spotFilter.and( spot -> tagsToProcess.contains( tagMapDetection.get( spot ) ) );
				spotFilter = spotFilter.and( spot -> spot.incomingEdges().size() == 0 );
				if ( mode == NearestNeighborLinkingActionMode.AROUND_MOUSE )
					spotFilter = spotFilter.and( spot -> ElephantUtils.spotIsInside( spot, cropBoxOrigin, cropBoxSize ) );
				addSpotsToJsonFlow( getGraph().vertices(), jsonSpots, spotFilter );
			}
			finally
			{
				getGraph().getLock().readLock().unlock();
			}
			if ( getMainSettings().getUseOpticalflow() )
			{
				jsonRootObject.set( JSON_KEY_TIMEPOINT, timepoint );
				jsonRootObject.set( JSON_KEY_SPOTS, jsonSpots );
				try
				{
					postAsStringAsync( getEndpointURL( ENDPOINT_FLOW_PREDICT ), jsonRootObject.toString(),
							response -> {
								if ( response.getStatus() == HttpURLConnection.HTTP_OK )
								{
									final JsonObject rootObject = Json.parse( response.getBody() ).asObject();
									if ( rootObject.get( "completed" ).asBoolean() )
									{
										final JsonArray jsonSpotsRes = rootObject.get( "spots" ).asArray();
										linkSpots( jsonSpotsRes, timepoint, tagsToProcess, timepointIterator, pos, cov );
										showTextOverlayAnimator( String.format( "Linked %d->%d", timepoint, timepoint - 1 ), 1000, TextPosition.BOTTOM_RIGHT );
									}
									if ( getActionStateManager().isAborted() )
										showTextOverlayAnimator( "Aborted", 3000, TextPosition.BOTTOM_RIGHT );
									else
										processNext( timepointIterator, pos, cov );
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
			else
			{
				linkSpots( jsonSpots, timepoint, tagsToProcess, timepointIterator, pos, cov );
				showTextOverlayAnimator( String.format( "Linked %d->%d", timepoint, timepoint - 1 ), 1000, TextPosition.BOTTOM_RIGHT );
				if ( getActionStateManager().isAborted() )
					showTextOverlayAnimator( "Aborted", 3000, TextPosition.BOTTOM_RIGHT );
				else
					processNext( timepointIterator, pos, cov );
			}

		}
	}

	private void linkSpots( final JsonArray jsonSpots, final int timepoint, final List< Tag > tagsToProcess, final Iterator< Integer > timepointIterator, final double[] pos, final double[][] cov )
	{
		final ObjTagMap< Spot, Tag > tagMapDetection = getVertexTagMap( getDetectionTagSet() );
		final ObjTagMap< Spot, Tag > tagMapTrackingSpot = getVertexTagMap( getTrackingTagSet() );
		final ObjTagMap< Link, Tag > tagMapTrackingLink = getEdgeTagMap( getTrackingTagSet() );
		final Tag detectionUnlabeledTag = getTag( getDetectionTagSet(), DETECTION_UNLABELED_TAG_NAME );
		final Tag trackingApprovedTag = getTag( getTrackingTagSet(), TRACKING_APPROVED_TAG_NAME );
		final Tag trackingUnlabeledTag = getTag( getTrackingTagSet(), TRACKING_UNLABELED_TAG_NAME );

		final List< Integer > linkedSpotIds = new ArrayList<>();
		final Map< Integer, Double > distMap = new HashMap<>();
		final Map< Integer, Double > sqDispMap = new HashMap<>();
		final Set< Integer > interpolatedIdSet = new HashSet<>();

		final boolean useInterpolation = getMainSettings().getUseInterpolation();
		final int searchDepth = getMainSettings().getNNSearchDepth();
		final int searchNeighbors = getMainSettings().getNNSearchNeighbors();
		final Spot sourceRef = getGraph().vertexRef();
		final Spot targetRef = getGraph().vertexRef();
		final Spot newSpotRef = getGraph().vertexRef();
		final Link edgeRef = getGraph().edgeRef();

		final RealPoint point = new RealPoint( 3 );

		final Comparator< Link > comparatorLink = new Comparator< Link >()
		{
			@Override
			public int compare( Link o1, Link o2 )
			{
				return Double.compare(
						distMap.getOrDefault( o1.getInternalPoolIndex(), squaredDistanceOf( o1 ) ),
						distMap.getOrDefault( o2.getInternalPoolIndex(), squaredDistanceOf( o2 ) ) );
			}

		};

		getGraph().getLock().readLock().lock();
		try
		{
			final RefList< Link > linksToRemove = RefCollections.createRefList( getGraph().edges() );
			final Supplier< Stream< Spot > > spotSupplier = () -> getGraph().vertices().stream().filter( s -> s.getTimepoint() == timepoint );
			for ( int n = 0; n < 5; n++ )
			{
				for ( final JsonValue jsonValue : jsonSpots )
				{
					final JsonObject jsonSpot = jsonValue.asObject();
					final int spotId = jsonSpot.get( "id" ).asInt();
					if ( linkedSpotIds.contains( spotId ) )
						continue;
					final Spot spot = spotSupplier.get().filter( s -> s.getInternalPoolIndex() == spotId ).findFirst().orElse( null );
					if ( spot == null )
					{
						getClientLogger().info( "spot " + spot + " was not found" );
					}
					else
					{
						final JsonArray jsonPositions = jsonSpot.get( "pos" ).asArray();
						double sqDisp = getMainSettings().getUseOpticalflow() ? jsonSpot.get( "sqdisp" ).asDouble() : 0;
						for ( int t = 0; t < searchDepth && 0 <= ( timepoint - 1 - t ); t++ )
						{
							final int timepointToSearch = timepoint - 1 - t;
							final SpatialIndex< Spot > spatialIndex = getSpatioTemporalIndex().getSpatialIndex( timepointToSearch );
							final IncrementalNearestNeighborSearch< Spot > inns = spatialIndex.getIncrementalNearestNeighborSearch();
							for ( int j = 0; j < 3; j++ )
								pos[ j ] = jsonPositions.get( j ).asDouble();
							point.setPosition( pos );
							inns.search( point );
							for ( int i = 0; inns.hasNext() && i < searchNeighbors; i++ )
							{
								final Spot nearestSpot = inns.next();
								if ( !tagsToProcess.contains( tagMapDetection.get( nearestSpot ) ) )
									continue;
								final double squaredDistance = inns.getSquareDistance();
								if ( squaredDistanceThreshold < squaredDistance )
									break;
								// TODO: Division detector
								if ( !getMainSettings().getUseOpticalflow() )
									sqDisp = squaredDistance;
								int acceptableEdges = 1.0 < sqDisp ? maxEdges : 1;
								for ( final Link edge : nearestSpot.outgoingEdges() )
								{
									if ( 1.0 < sqDispMap.getOrDefault( edge.getInternalPoolIndex(), 0.0 ) )
										acceptableEdges = maxEdges;
									if ( ( squaredDistance < 1.0 ) && ( distMap.getOrDefault( edge.getInternalPoolIndex(), 0.0 ) < 1.0 ) )
										acceptableEdges = maxEdges;
								}
								final Supplier< Stream< Link > > edgeSupplier = () -> StreamSupport.stream( nearestSpot.outgoingEdges().spliterator(), false );
								final long nApprovedEdges = edgeSupplier.get().filter( edge -> tagMapTrackingLink.get( edge ) == trackingApprovedTag ).count();
								if ( nApprovedEdges < acceptableEdges )
								{
									if ( acceptableEdges <= edgeSupplier.get().count() )
									{
										final Link longestEdge = edgeSupplier.get().filter( edge -> tagMapTrackingLink.get( edge ) != trackingApprovedTag ).max( comparatorLink ).orElse( null );
										if ( longestEdge != null )
										{
											if ( distMap.getOrDefault( longestEdge.getInternalPoolIndex(), squaredDistanceOf( longestEdge ) ) < squaredDistance )
												continue;
											else
											{
												edgeRef.refTo( longestEdge );
												edgeRef.getSource( sourceRef );
												edgeRef.getTarget( targetRef );
												if ( 0 < sourceRef.getInternalPoolIndex() && 0 < targetRef.getInternalPoolIndex() )
												{
													linksToRemove.add( edgeRef );
													linkedSpotIds.remove( ( Integer ) targetRef.getInternalPoolIndex() );
												}
											}
										}
									}
									if ( useInterpolation && ( 0 < t ) && !interpolatedIdSet.contains( spotId ) )
									{
										spot.getCovariance( cov );
										getGraph().getLock().readLock().unlock();
										getGraph().getLock().writeLock().lock();
										getActionStateManager().setWriting( true );
										try
										{
											final Spot newSpot = getGraph().addVertex( newSpotRef ).init( timepoint - 1, pos, cov );
											tagMapDetection.set( newSpot, detectionUnlabeledTag );
											tagMapTrackingSpot.set( newSpot, trackingUnlabeledTag );
											nearestSpot.refTo( newSpotRef );
											interpolatedIdSet.add( spot.getInternalPoolIndex() );
											getGraph().getLock().readLock().lock();
										}
										finally
										{
											getActionStateManager().setWriting( false );
											getGraph().getLock().writeLock().unlock();
										}
									}
									getGraph().getLock().readLock().unlock();
									getGraph().getLock().writeLock().lock();
									getActionStateManager().setWriting( true );
									try
									{
										final Link edge = getGraph().addEdge( nearestSpot, spot ).init();
										tagMapTrackingLink.set( edge, trackingUnlabeledTag );
										linkedSpotIds.add( spot.getInternalPoolIndex() );
										distMap.put( edge.getInternalPoolIndex(), squaredDistance );
										sqDispMap.put( edge.getInternalPoolIndex(), sqDisp );
										getGraph().getLock().readLock().lock();
									}
									finally
									{
										getActionStateManager().setWriting( false );
										getGraph().getLock().writeLock().unlock();
									}
									break;
								}
							}
							if ( linkedSpotIds.contains( spotId ) )
								break;
						}
					}
				}
			}
			getGraph().getLock().readLock().unlock();
			getGraph().getLock().writeLock().lock();
			getActionStateManager().setWriting( true );
			try
			{
				for ( final Link edge : linksToRemove )
				{
					edge.getSource( sourceRef );
					edge.getTarget( targetRef );
					if ( 0 < sourceRef.getInternalPoolIndex() && 0 < targetRef.getInternalPoolIndex() )
						getGraph().remove( edge );
				}
				getGraph().getLock().readLock().lock();
			}
			finally
			{
				getActionStateManager().setWriting( false );
				getGraph().getLock().writeLock().unlock();
			}
			getGraph().releaseRef( sourceRef );
			getGraph().releaseRef( targetRef );
			getGraph().releaseRef( newSpotRef );
		}
		catch ( final Exception e )
		{
			getClientLogger().severe( ExceptionUtils.getStackTrace( e ) );
		}
		finally
		{
			getModel().setUndoPoint();
			getGraph().getLock().readLock().unlock();
			notifyGraphChanged();
		}
	}

	private void addSpotsToJsonFlow( final Collection< Spot > spots, final JsonArray jsonSpots, Predicate< Spot > filter )
	{
		final double[] pos = new double[ 3 ];
		final double[][] cov = new double[ 3 ][ 3 ];
		final double[] cov1d = new double[ 9 ];
		getGraph().getLock().readLock().lock();
		try
		{
			for ( final Spot spot : spots )
			{
				if ( filter == null || filter.test( spot ) )
				{
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
					jsonSpots.add( jsonSpot );
				}
			}
		}
		finally
		{
			getGraph().getLock().readLock().unlock();
		}
	}

}
