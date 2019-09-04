/*
 * Copyright (c) 2002-2019 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.pushtocloud;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.neo4j.commandline.admin.CommandFailed;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.test.rule.TestDirectory;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static java.lang.String.format;
import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_BAD_GATEWAY;
import static java.net.HttpURLConnection.HTTP_CONFLICT;
import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.neo4j.pushtocloud.HttpCopier.HTTP_RESUME_INCOMPLETE;

public class HttpCopierTest
{
    private static final int TEST_PORT = 8080;
    private static final String TEST_CONSOLE_URL = "http://localhost:" + TEST_PORT;

    private final DefaultFileSystemAbstraction fs = new DefaultFileSystemAbstraction();
    @Rule
    public WireMockRule wireMock = new WireMockRule( TEST_PORT );
    @Rule
    public TestDirectory directory = TestDirectory.testDirectory();

    @Test
    public void shouldHandleSuccessfulHappyCaseRunThroughOfTheWholeProcess() throws Exception
    {
        // given
        HttpCopier copier = new HttpCopier( new ControlledOutsideWorld( fs ) );
        Path source = createDump();
        long sourceLength = fs.getFileSize( source.toFile() );

        String authorizationTokenResponse = "abc";
        String signedURIPath = "/signed";
        String uploadLocationPath = "/upload";
        wireMock.stubFor( authenticationRequest().willReturn( successfulAuthorizationResponse( authorizationTokenResponse ) ) );
        wireMock.stubFor( initiateUploadTargetRequest( authorizationTokenResponse, false )
                .willReturn( successfulInitiateUploadTargetResponse( signedURIPath ) ) );
        wireMock.stubFor( initiateUploadRequest( signedURIPath ).willReturn( successfulInitiateUploadResponse( uploadLocationPath ) ) );
        wireMock.stubFor( resumeUploadRequest( uploadLocationPath, sourceLength ).willReturn( successfulResumeUploadResponse() ) );
        wireMock.stubFor( triggerImportRequest( authorizationTokenResponse ).willReturn( successfulTriggerImportResponse() ) );

        // TODO there will be some pinging the console about status here in the future

        // when
        copier.copy( false, TEST_CONSOLE_URL, source, "user", "pass".toCharArray() );

        // then
        verify( postRequestedFor( urlEqualTo( "/import/auth" ) ) );
        verify( postRequestedFor( urlEqualTo( "/import" ) ) );
        verify( postRequestedFor( urlEqualTo( signedURIPath ) ) );
        verify( putRequestedFor( urlEqualTo( uploadLocationPath ) ) );
        verify( postRequestedFor( urlEqualTo( "/import/upload-complete" ) ) );
    }

    @Test
    public void shouldHandleBadCredentialsInAuthorizationRequest() throws IOException
    {
        // given
        HttpCopier copier = new HttpCopier( new ControlledOutsideWorld( fs ) );
        Path source = createDump();
        wireMock.stubFor( authenticationRequest().willReturn( aResponse()
                .withStatus( HTTP_UNAUTHORIZED ) ) );

        // when/then
        assertThrows( CommandFailed.class, CoreMatchers.equalTo( "Invalid username/password credentials" ),
                () -> copier.copy( false, TEST_CONSOLE_URL, source, "user", "pass".toCharArray() ) );
    }

    @Test
    public void shouldHandleInsufficientCredentialsInAuthorizationRequest() throws IOException
    {
        // given
        HttpCopier copier = new HttpCopier( new ControlledOutsideWorld( fs ) );
        Path source = createDump();
        wireMock.stubFor( authenticationRequest().willReturn( aResponse()
                .withStatus( HTTP_FORBIDDEN ) ) );

        // when/then
        assertThrows( CommandFailed.class, containsString( "administrative access" ),
                () -> copier.copy( false, TEST_CONSOLE_URL, source, "user", "pass".toCharArray() ) );
    }

    @Test
    public void shouldHandleUnexpectedResponseFromAuthorizationRequest() throws IOException
    {
        // given
        HttpCopier copier = new HttpCopier( new ControlledOutsideWorld( fs ) );
        Path source = createDump();
        wireMock.stubFor( authenticationRequest().willReturn( aResponse()
                .withStatus( HTTP_INTERNAL_ERROR ) ) );

        // when/then
        assertThrows( CommandFailed.class, allOf( containsString( "Unexpected response" ), containsString( "Authorization" ) ),
                () -> copier.copy( false, TEST_CONSOLE_URL, source, "user", "pass".toCharArray() ) );
    }

    @Test
    public void shouldHandleUnauthorizedResponseFromInitiateUploadTarget() throws IOException
    {
        HttpCopier copier = new HttpCopier( new ControlledOutsideWorld( fs ) );
        Path source = createDump();
        String token = "abc";
        wireMock.stubFor( authenticationRequest().willReturn( successfulAuthorizationResponse( token ) ) );
        wireMock.stubFor( initiateUploadTargetRequest( token, false ).willReturn( aResponse()
                .withStatus( HTTP_UNAUTHORIZED ) ) );

        // when/then
        assertThrows( CommandFailed.class, containsString( "authorization token is invalid" ),
                () -> copier.copy( false, TEST_CONSOLE_URL, source, "user", "pass".toCharArray() ) );
    }

    @Test
    public void shouldHandleConflictResponseFromInitiateUploadTargetAndContinueOnUserConsent() throws IOException, CommandFailed
    {
        ControlledOutsideWorld outsideWorld = new ControlledOutsideWorld( fs );
        outsideWorld.withPromptResponse( "my-username" ); // prompt for username
        outsideWorld.withPromptResponse( "y" ); // prompt for consent to overwrite db
        HttpCopier copier = new HttpCopier( outsideWorld );
        Path source = createDump();
        long sourceLength = fs.getFileSize( source.toFile() );
        String authorizationTokenResponse = "abc";
        String signedURIPath = "/signed";
        String uploadLocationPath = "/upload";
        wireMock.stubFor( authenticationRequest().willReturn( successfulAuthorizationResponse( authorizationTokenResponse ) ) );
        wireMock.stubFor( initiateUploadTargetRequest( authorizationTokenResponse, false ).willReturn( aResponse()
                .withStatus( HTTP_CONFLICT ) ) );
        wireMock.stubFor( initiateUploadTargetRequest( authorizationTokenResponse, true )
                .willReturn( successfulInitiateUploadTargetResponse( signedURIPath ) ) );
        // and just the rest of the responses so that the upload can continue w/o failing
        wireMock.stubFor( initiateUploadRequest( signedURIPath ).willReturn( successfulInitiateUploadResponse( uploadLocationPath ) ) );
        wireMock.stubFor( resumeUploadRequest( uploadLocationPath, sourceLength ).willReturn( successfulResumeUploadResponse() ) );
        wireMock.stubFor( triggerImportRequest( authorizationTokenResponse ).willReturn( successfulTriggerImportResponse() ) );

        // when
        copier.copy( false, TEST_CONSOLE_URL, source, "user", "pass".toCharArray() );

        // then there should be one request w/o the user consent and then (since the user entered 'y') one w/ user consent
        verify( postRequestedFor( urlEqualTo( "/import" ) ).withRequestBody( notMatching( ".*Confirmed.*" ) ) );
        verify( postRequestedFor( urlEqualTo( "/import" ) ).withRequestBody( containing( "Confirmed" ) ) );
    }

    @Test
    public void shouldHandleConflictResponseFromInitiateUploadTargetWithoutUserConsent() throws IOException
    {
        ControlledOutsideWorld outsideWorld = new ControlledOutsideWorld( fs );
        outsideWorld.withPromptResponse( "my-username" ); // prompt for username
        outsideWorld.withPromptResponse( "n" ); // prompt for consent to overwrite db
        HttpCopier copier = new HttpCopier( outsideWorld );
        Path source = createDump();
        String authorizationTokenResponse = "abc";
        String signedURIPath = "/signed";
        wireMock.stubFor( authenticationRequest().willReturn( successfulAuthorizationResponse( authorizationTokenResponse ) ) );
        wireMock.stubFor( initiateUploadTargetRequest( authorizationTokenResponse, false ).willReturn( aResponse()
                .withStatus( HTTP_CONFLICT ) ) );
        wireMock.stubFor( initiateUploadTargetRequest( authorizationTokenResponse, true )
                .willReturn( successfulInitiateUploadTargetResponse( signedURIPath ) ) );

        // when
        assertThrows( CommandFailed.class, containsString( "No consent to overwrite" ),
                () -> copier.copy( false, TEST_CONSOLE_URL, source, "user", "pass".toCharArray() ) );

        // then there should be one request w/o the user consent and then (since the user entered 'y') one w/ user consent
        verify( postRequestedFor( urlEqualTo( "/import" ) ).withRequestBody( notMatching( ".*Confirmed.*" ) ) );
        verify( 0, postRequestedFor( urlEqualTo( "/import" ) ).withRequestBody( containing( "Confirmed" ) ) );
    }

    @Test
    public void shouldHandleUnexpectedResponseFromInitiateUploadTargetRequest() throws IOException
    {
        ControlledOutsideWorld outsideWorld = new ControlledOutsideWorld( fs );
        outsideWorld.withPromptResponse( "my-username" ); // prompt for username
        outsideWorld.withPromptResponse( "n" ); // prompt for consent to overwrite db
        HttpCopier copier = new HttpCopier( outsideWorld );
        Path source = createDump();
        String authorizationTokenResponse = "abc";
        wireMock.stubFor( authenticationRequest().willReturn( successfulAuthorizationResponse( authorizationTokenResponse ) ) );
        wireMock.stubFor( initiateUploadTargetRequest( authorizationTokenResponse, false ).willReturn( aResponse()
                .withStatus( HTTP_BAD_GATEWAY ) ) );

        // when
        assertThrows( CommandFailed.class, allOf( containsString( "Unexpected response" ), containsString( "Initiating upload target" ) ),
                () -> copier.copy( false, TEST_CONSOLE_URL, source, "user", "pass".toCharArray() ) );
    }

    @Test
    public void shouldHandleInitiateUploadFailure() throws IOException
    {
        HttpCopier copier = new HttpCopier( new ControlledOutsideWorld( fs ) );
        Path source = createDump();
        String authorizationTokenResponse = "abc";
        String signedURIPath = "/signed";
        wireMock.stubFor( authenticationRequest().willReturn( successfulAuthorizationResponse( authorizationTokenResponse ) ) );
        wireMock.stubFor( initiateUploadTargetRequest( authorizationTokenResponse, false )
                .willReturn( successfulInitiateUploadTargetResponse( signedURIPath ) ) );
        wireMock.stubFor( initiateUploadRequest( signedURIPath ).willReturn( aResponse()
                .withStatus( HTTP_INTERNAL_ERROR ) ) );

        // when
        assertThrows( CommandFailed.class, allOf( containsString( "Unexpected response" ), containsString( "Initiating database upload" ) ),
                () -> copier.copy( false, TEST_CONSOLE_URL, source, "user", "pass".toCharArray() ) );
    }

    @Test
    public void shouldHandleUploadInACoupleOfRounds() throws IOException, CommandFailed
    {
        HttpCopier copier = new HttpCopier( new ControlledOutsideWorld( fs ), millis -> {} );
        Path source = createDump();
        long sourceLength = fs.getFileSize( source.toFile() );
        long firstUploadLength = sourceLength / 3;
        String authorizationTokenResponse = "abc";
        String signedURIPath = "/signed";
        String uploadLocationPath = "/upload";
        wireMock.stubFor( authenticationRequest().willReturn( successfulAuthorizationResponse( authorizationTokenResponse ) ) );
        wireMock.stubFor( initiateUploadTargetRequest( authorizationTokenResponse, false )
                .willReturn( successfulInitiateUploadTargetResponse( signedURIPath ) ) );
        wireMock.stubFor( initiateUploadRequest( signedURIPath ).willReturn( successfulInitiateUploadResponse( uploadLocationPath ) ) );
        wireMock.stubFor( resumeUploadRequest( uploadLocationPath, 0, sourceLength ).willReturn( aResponse()
                .withStatus( HTTP_INTERNAL_ERROR ) ) );
        wireMock.stubFor( getResumablePositionRequest( sourceLength, uploadLocationPath )
                .willReturn( uploadIncompleteGetResumablePositionResponse( firstUploadLength ) ) );
        wireMock.stubFor( resumeUploadRequest( uploadLocationPath, firstUploadLength, sourceLength )
                .willReturn( successfulResumeUploadResponse() ) );
        wireMock.stubFor( triggerImportRequest( authorizationTokenResponse ).willReturn( successfulTriggerImportResponse() ) );

        // when
        copier.copy( false, TEST_CONSOLE_URL, source, "user", "pass".toCharArray() );

        // then
        verify( putRequestedFor( urlEqualTo( uploadLocationPath ) )
                .withHeader( "Content-Length", equalTo( Long.toString( sourceLength ) ) )
                .withoutHeader( "Content-Range" ) );
        verify( putRequestedFor( urlEqualTo( uploadLocationPath ) )
                .withHeader( "Content-Length", equalTo( Long.toString( sourceLength - firstUploadLength ) ) )
                .withHeader( "Content-Range", equalTo( format( "bytes %d-%d/%d", firstUploadLength, sourceLength - 1, sourceLength ) ) ) );
        verify( putRequestedFor( urlEqualTo( uploadLocationPath ) )
                .withHeader( "Content-Length", equalTo( "0" ) )
                .withHeader( "Content-Range", equalTo( "bytes */" + sourceLength ) ) );
    }

    @Test
    public void shouldHandleIncompleteUploadButPositionSaysComplete() throws IOException, CommandFailed
    {
        HttpCopier copier = new HttpCopier( new ControlledOutsideWorld( fs ), millis -> {} );
        Path source = createDump();
        long sourceLength = fs.getFileSize( source.toFile() );
        String authorizationTokenResponse = "abc";
        String signedURIPath = "/signed";
        String uploadLocationPath = "/upload";
        wireMock.stubFor( authenticationRequest().willReturn( successfulAuthorizationResponse( authorizationTokenResponse ) ) );
        wireMock.stubFor( initiateUploadTargetRequest( authorizationTokenResponse, false )
                .willReturn( successfulInitiateUploadTargetResponse( signedURIPath ) ) );
        wireMock.stubFor( initiateUploadRequest( signedURIPath ).willReturn( successfulInitiateUploadResponse( uploadLocationPath ) ) );
        wireMock.stubFor( resumeUploadRequest( uploadLocationPath, 0, sourceLength ).willReturn( aResponse()
                .withStatus( HTTP_INTERNAL_ERROR ) ) );
        wireMock.stubFor( getResumablePositionRequest( sourceLength, uploadLocationPath )
                .willReturn( uploadCompleteGetResumablePositionResponse() ) );
        wireMock.stubFor( triggerImportRequest( authorizationTokenResponse ).willReturn( successfulTriggerImportResponse() ) );

        // when
        copier.copy( false, TEST_CONSOLE_URL, source, "user", "pass".toCharArray() );

        // then
        verify( putRequestedFor( urlEqualTo( uploadLocationPath ) )
                .withHeader( "Content-Length", equalTo( Long.toString( sourceLength ) ) )
                .withoutHeader( "Content-Range" ) );
        verify( putRequestedFor( urlEqualTo( uploadLocationPath ) )
                .withHeader( "Content-Length", equalTo( "0" ) )
                .withHeader( "Content-Range", equalTo( "bytes */" + sourceLength ) ) );
    }

    @Test
    public void shouldHandleConflictOnTriggerImportAfterUpload() throws IOException
    {
        // given
        HttpCopier copier = new HttpCopier( new ControlledOutsideWorld( fs ) );
        Path source = createDump();
        long sourceLength = fs.getFileSize( source.toFile() );
        String authorizationTokenResponse = "abc";
        String signedURIPath = "/signed";
        String uploadLocationPath = "/upload";
        wireMock.stubFor( authenticationRequest().willReturn( successfulAuthorizationResponse( authorizationTokenResponse ) ) );
        wireMock.stubFor( initiateUploadTargetRequest( authorizationTokenResponse, false )
                .willReturn( successfulInitiateUploadTargetResponse( signedURIPath ) ) );
        wireMock.stubFor( initiateUploadRequest( signedURIPath ).willReturn( successfulInitiateUploadResponse( uploadLocationPath ) ) );
        wireMock.stubFor( resumeUploadRequest( uploadLocationPath, sourceLength ).willReturn( successfulResumeUploadResponse() ) );
        wireMock.stubFor( triggerImportRequest( authorizationTokenResponse ).willReturn( aResponse()
                .withStatus( HTTP_CONFLICT ) ) );

        // when
        assertThrows( CommandFailed.class, containsString( "A non-empty database already exists" ),
                () -> copier.copy( false, TEST_CONSOLE_URL, source, "user", "pass".toCharArray() ) );
    }

    @Test
    public void shouldBackoffAndFailIfTooManyAttempts() throws IOException, InterruptedException
    {
        // given
        HttpCopier.Sleeper sleeper = mock( HttpCopier.Sleeper.class );
        HttpCopier copier = new HttpCopier( new ControlledOutsideWorld( fs ), sleeper );
        Path source = createDump();
        long sourceLength = fs.getFileSize( source.toFile() );
        String authorizationTokenResponse = "abc";
        String signedURIPath = "/signed";
        String uploadLocationPath = "/upload";
        wireMock.stubFor( authenticationRequest().willReturn( successfulAuthorizationResponse( authorizationTokenResponse ) ) );
        wireMock.stubFor( initiateUploadTargetRequest( authorizationTokenResponse, false )
                .willReturn( successfulInitiateUploadTargetResponse( signedURIPath ) ) );
        wireMock.stubFor( initiateUploadRequest( signedURIPath ).willReturn( successfulInitiateUploadResponse( uploadLocationPath ) ) );
        wireMock.stubFor( resumeUploadRequest( uploadLocationPath, sourceLength ).willReturn( aResponse()
                .withStatus( HTTP_INTERNAL_ERROR ) ) );
        wireMock.stubFor( getResumablePositionRequest( sourceLength, uploadLocationPath )
                .willReturn( uploadIncompleteGetResumablePositionResponse( 0 ) ) );

        // when/then
        assertThrows( CommandFailed.class, containsString( "Upload failed after numerous attempts" ),
                () -> copier.copy( false, TEST_CONSOLE_URL, source, "user", "pass".toCharArray() ) );
        Mockito.verify( sleeper, atLeast( 30 ) ).sleep( anyLong() );
    }

    private MappingBuilder authenticationRequest()
    {
        return post( urlEqualTo( "/import/auth" ) )
                .withHeader( "Authorization", matching( "^Basic .*" ) )
                .withHeader( "Accept", equalTo( "application/json" ) );
    }

    private ResponseDefinitionBuilder successfulAuthorizationResponse( String authorizationTokenResponse )
    {
        return aResponse()
                .withStatus( HTTP_OK )
                .withBody( format( "{\"Token\":\"%s\"}", authorizationTokenResponse ) );
    }

    private MappingBuilder initiateUploadTargetRequest( String authorizationTokenResponse, boolean userConsent )
    {
        return post( urlEqualTo( "/import" ) )
                .withHeader( "Content-Type", equalTo( "application/json" ) )
                .withHeader( "Authorization", equalTo( "Bearer " + authorizationTokenResponse ) )
                .withHeader( "Accept", equalTo( "application/json" ) )
                .withRequestBody( userConsent ? containing( "Confirmed" ) : notMatching( ".*Confirmed.*" ) );
    }

    private ResponseDefinitionBuilder successfulInitiateUploadTargetResponse( String signedURIPath )
    {
        return aResponse()
                .withStatus( HTTP_ACCEPTED )
                .withBody( format( "{\"SignedURI\":\"%s\"}", TEST_CONSOLE_URL + signedURIPath ) );
    }

    private MappingBuilder initiateUploadRequest( String signedURIPath )
    {
        return post( urlEqualTo( signedURIPath ) )
                .withHeader( "Content-Length", equalTo( "0" ) )
                .withHeader( "x-goog-resumable", equalTo( "start" ) );
    }

    private ResponseDefinitionBuilder successfulInitiateUploadResponse( String uploadLocationPath )
    {
        return aResponse()
                .withStatus( HTTP_CREATED )
                .withHeader( "Location", TEST_CONSOLE_URL + uploadLocationPath );
    }

    private MappingBuilder resumeUploadRequest( String uploadLocationPath, long length )
    {
        return resumeUploadRequest( uploadLocationPath, 0, length );
    }

    private MappingBuilder resumeUploadRequest( String uploadLocationPath, long position, long length )
    {
        MappingBuilder builder = put( urlEqualTo( uploadLocationPath ) )
                .withHeader( "Content-Length", equalTo( Long.toString( length - position ) ) );
        if ( position > 0 )
        {
            builder = builder.withHeader( "Content-Range", equalTo( format( "bytes %d-%d/%d", position, length - 1, length ) ) );
        }
        return builder;
    }

    private ResponseDefinitionBuilder successfulResumeUploadResponse()
    {
        return aResponse()
                .withStatus( HTTP_OK );
    }

    private MappingBuilder triggerImportRequest( String authorizationTokenResponse )
    {
        return post( urlEqualTo( "/import/upload-complete" ) )
                .withHeader( "Content-Type", equalTo( "application/json" ) )
                .withHeader( "Authorization", equalTo( "Bearer " + authorizationTokenResponse ) )
                .withRequestBody( containing( "Crc32" ) );
    }

    private ResponseDefinitionBuilder successfulTriggerImportResponse()
    {
        return aResponse()
                .withStatus( HTTP_OK );
    }

    private ResponseDefinitionBuilder uploadIncompleteGetResumablePositionResponse( long bytesUploadedSoFar )
    {
        return aResponse()
                .withStatus( HTTP_RESUME_INCOMPLETE )
                .withHeader( "Range", "bytes=0-" + (bytesUploadedSoFar - 1) );
    }

    private ResponseDefinitionBuilder uploadCompleteGetResumablePositionResponse()
    {
        return aResponse()
                .withStatus( HTTP_CREATED );
    }

    private MappingBuilder getResumablePositionRequest( long sourceLength, String uploadLocationPath )
    {
        return put( urlEqualTo( uploadLocationPath ) )
                .withHeader( "Content-Length", equalTo( "0" ) )
                .withHeader( "Content-Range", equalTo( "bytes */" + sourceLength ) );
    }

    private Path createDump() throws IOException
    {
        File file = directory.file( "something" );
        assertTrue( file.createNewFile() );
        Files.write( file.toPath(), "this is simply some weird dump data, but may do the trick for this test of uploading it".getBytes() );
        return file.toPath();
    }

    private static void assertThrows( Class<? extends Exception> exceptionClass, Matcher<String> message, ThrowingRunnable action )
    {
        try
        {
            action.run();
            fail( "Should have failed" );
        }
        catch ( Exception e )
        {
            assertTrue( exceptionClass.isInstance( e ) );
            assertThat( e.getMessage(), message );
        }
    }

    private interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
