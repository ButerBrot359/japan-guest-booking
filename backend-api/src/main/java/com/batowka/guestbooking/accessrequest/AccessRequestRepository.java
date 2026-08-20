package com.batowka.guestbooking.accessrequest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccessRequestRepository extends JpaRepository<AccessRequest, Long> {

    List<AccessRequest> findAllByStatusOrderByIdDesc(AccessRequestStatus status);
}
