package com.codesight.search.api;

import com.codesight.search.api.dto.request.SearchRequest;
import com.codesight.search.api.dto.response.SearchResponse;
import com.codesight.search.service.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private SearchService searchService;

    @InjectMocks
    private SearchController searchController;

    @Test
    void shouldCallSearchServiceWhenRequested() {
        SearchRequest request = new SearchRequest("Java", 20, null);
        SearchResponse mockResponse = new SearchResponse(Collections.emptyList(), null, false);
        when(searchService.search(eq(request), isNull())).thenReturn(mockResponse);

        SearchResponse response = searchController.search(request, null);

        assertNotNull(response);
        assertEquals(mockResponse, response);
        verify(searchService, times(1)).search(eq(request), isNull());
    }

    @Test
    void shouldPassCurrentUserIdToSearchService() {
        SearchRequest request = new SearchRequest("Elasticsearch", 10, "cursor123");
        Long userId = 888L;
        SearchResponse mockResponse = new SearchResponse(Collections.emptyList(), null, false);
        when(searchService.search(eq(request), eq(userId))).thenReturn(mockResponse);

        SearchResponse response = searchController.search(request, userId);

        assertNotNull(response);
        assertEquals(mockResponse, response);
        verify(searchService, times(1)).search(eq(request), eq(userId));
    }

    @Test
    void shouldCreateSearchRequestWithProvidedParameters() {
        SearchRequest request = new SearchRequest("Java", 20, "cursor123");
        assertEquals("Java", request.q());
        assertEquals(20, request.size());
        assertEquals("cursor123", request.after());
    }
}
