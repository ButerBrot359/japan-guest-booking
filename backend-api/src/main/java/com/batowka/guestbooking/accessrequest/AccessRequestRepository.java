package com.batowka.guestbooking.accessrequest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccessRequestRepository extends JpaRepository<AccessRequest, Long> {

    boolean existsByPhoneAndStatus(String phone, AccessRequestStatus status);

    List<AccessRequest> findAllByStatusOrderByIdDesc(AccessRequestStatus status);
}
