package com.example.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data @NoArgsConstructor
public class EventMessageDetail {
	
	private int id;
	private String severity;

	public EventMessageDetail(int id, String severity) {		
		this.id = id;
		this.severity = severity;
	}
}
