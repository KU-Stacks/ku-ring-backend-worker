package com.kustacks.kuring.worker.updater.notice;

import com.kustacks.kuring.worker.error.ErrorCode;
import com.kustacks.kuring.worker.error.InternalLogicException;
import com.kustacks.kuring.worker.persistence.category.Category;
import com.kustacks.kuring.worker.persistence.notice.Notice;
import com.kustacks.kuring.worker.persistence.notice.NoticeRepository;
import com.kustacks.kuring.worker.updater.CategoryName;
import com.kustacks.kuring.worker.updater.api.notice.NoticeAPIClient;
import com.kustacks.kuring.worker.updater.api.scrap.NoticeScraper;
import com.kustacks.kuring.worker.updater.deptinfo.DeptInfo;
import com.kustacks.kuring.worker.updater.mq.MQNotifierProducer;
import com.kustacks.kuring.worker.updater.mq.dto.NewNoticeMQMessageDTO;
import com.kustacks.kuring.worker.updater.notice.dto.response.CommonNoticeFormatDTO;
import com.kustacks.kuring.worker.updater.util.converter.DTOConverter;
import com.kustacks.kuring.worker.updater.util.converter.DateConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class NewNoticeUpdater {

    @Value("${notice.normal-base-url}")
    private String normalBaseUrl;

    @Value("${notice.library-base-url}")
    private String libraryBaseUrl;

    private final Map<CategoryName, NoticeAPIClient<CommonNoticeFormatDTO, CategoryName>> noticeAPIClientMap;
    private final Map<CategoryName, DeptInfo> categoryNameDeptInfoMap;
    private final NoticeScraper scraper;
    private final DTOConverter<NewNoticeMQMessageDTO, Notice> noticeEntityToNewNoticeMQMessageDTOConverter;
    private final DTOConverter<Notice, CommonNoticeFormatDTO> commonNoticeFormatDTOToNoticeEntityConverter;
    private final DateConverter<String, String> ymdhmsToYmdConverter;
    private final NoticeRepository noticeRepository;
    private final MQNotifierProducer<NewNoticeMQMessageDTO> mqNotifierProducer;
    private final Map<String, Category> categoryMap;

    public NewNoticeUpdater(Map<CategoryName, NoticeAPIClient<CommonNoticeFormatDTO, CategoryName>> noticeAPIClientMap,
                            Map<CategoryName, DeptInfo> categoryNameDeptInfoMap,
                            NoticeScraper noticeScraper,
                            DTOConverter<NewNoticeMQMessageDTO, Notice> noticeEntityToNewNoticeMQMessageDTOConverter,
                            DTOConverter<Notice, CommonNoticeFormatDTO> commonNoticeFormatDTOToNoticeEntityConverter,
                            DateConverter<String, String> ymdhmsToYmdConverter,

                            NoticeRepository noticeRepository,

                            MQNotifierProducer<NewNoticeMQMessageDTO> rabbitMQUpdaterProducer,
                            Map<String, Category> categoryMap
    ) {

        this.noticeAPIClientMap = noticeAPIClientMap;
        this.categoryNameDeptInfoMap = categoryNameDeptInfoMap;
        this.scraper = noticeScraper;
        this.noticeEntityToNewNoticeMQMessageDTOConverter = noticeEntityToNewNoticeMQMessageDTOConverter;
        this.commonNoticeFormatDTOToNoticeEntityConverter = commonNoticeFormatDTOToNoticeEntityConverter;
        this.ymdhmsToYmdConverter = ymdhmsToYmdConverter;

        this.noticeRepository = noticeRepository;

        this.mqNotifierProducer = rabbitMQUpdaterProducer;
        this.categoryMap = categoryMap;
    }

    public void update(CategoryName categoryName, boolean isFindingNew) {

        log.info("******** {} 공지 업데이트 시작 ********", categoryName.getKorName());

        /*
            학사, 장학, 취창업, 국제, 학생, 산학, 일반, 도서관 카테고리
            +
            각 학과 공지 업데이트
         */

        List<CommonNoticeFormatDTO> commonNoticeFormatDTOList;
        if(CategoryName.BACHELOR.equals(categoryName) ||
                CategoryName.SCHOLARSHIP.equals(categoryName) ||
                CategoryName.EMPLOYMENT.equals(categoryName) ||
                CategoryName.NATIONAL.equals(categoryName) ||
                CategoryName.STUDENT.equals(categoryName) ||
                CategoryName.INDUSTRY_UNIV.equals(categoryName) ||
                CategoryName.NORMAL.equals(categoryName) ||
                CategoryName.LIBRARY.equals(categoryName)
        ) {
            commonNoticeFormatDTOList = noticeAPIClientMap.get(categoryName).request(categoryName, isFindingNew);
        } else {
            DeptInfo deptInfo = categoryNameDeptInfoMap.get(categoryName);
            commonNoticeFormatDTOList = scraper.scrap(deptInfo, isFindingNew);
        }

        if(!CategoryName.LIBRARY.equals(categoryName)) {
            convertPostedDateToyyyyMMdd(commonNoticeFormatDTOList, categoryName);
        }

        // 새 공지로 인식된 데이터들을 NewNoticeMQMessageDTO형태로 변환
        // 그 과정에서 도서관 카테고리의 postedDate를 yyyyMMdd로 변경한다.
        List<Notice> willBeNotiNotices = compareAndUpdateDB(commonNoticeFormatDTOList, categoryName);
        List<NewNoticeMQMessageDTO> willBeNotiNoticeDTOList = new ArrayList<>(willBeNotiNotices.size());
        for (Notice notice : willBeNotiNotices) {
            NewNoticeMQMessageDTO messageDTO = noticeEntityToNewNoticeMQMessageDTOConverter.convert(notice);
            if(CategoryName.LIBRARY.getName().equals(notice.getCategory().getName())) {
                // library 카테고리는 yyyy-MM-dd HH:mm:ss 형식인데, FCM 메세지를 보낼 땐 이를 yyyyMMdd로 바꾸어주어야 한다.
                messageDTO.setPostedDate(ymdhmsToYmdConverter.convert(messageDTO.getPostedDate()));
            }
            willBeNotiNoticeDTOList.add(messageDTO);
        }

        // MQ로 새롭게 수신한 공지 데이터 전송
        try {
            mqNotifierProducer.publish(willBeNotiNoticeDTOList);
            if(!willBeNotiNoticeDTOList.isEmpty()) {
                log.info("MQ에 정상적으로 메세지를 전송했습니다.");
                log.info("전송된 공지 목록은 다음과 같습니다.");
                for (NewNoticeMQMessageDTO messageDTO : willBeNotiNoticeDTOList) {
                    log.info("아이디 = {}, 날짜 = {}, 카테고리 = {}, 제목 = {}", messageDTO.getArticleId(), messageDTO.getPostedDate(), messageDTO.getCategory(), messageDTO.getSubject());
                }
            }
        } catch(IOException | TimeoutException e) {
            log.error("새로운 공지의 MQ 전송에 실패했습니다.");
            throw new InternalLogicException(ErrorCode.FB_FAIL_SEND, e);
        } catch(Exception e) {
            log.error("새로운 공지를 MQ에 보내는 중 알 수 없는 오류가 발생했습니다.");
            throw new InternalLogicException(ErrorCode.UNKNOWN, e);
        }

        log.info("******** {} 공지 업데이트 종료 ********", categoryName.getKorName());
    }

    private void convertPostedDateToyyyyMMdd(List<CommonNoticeFormatDTO> commonNoticeFormatDTOList, CategoryName categoryName) {

        for (CommonNoticeFormatDTO notice : commonNoticeFormatDTOList) {
            try {
                String converted = ymdhmsToYmdConverter.convert(notice.getPostedDate());
                notice.setPostedDate(converted);
            } catch(Exception e) {
                log.info("에러 발생 공지 내용");
                log.info("아이디 = {}", notice.getArticleId());
                log.info("게시일 = {}", notice.getPostedDate());
                log.info("제목 = {}", notice.getSubject());
                log.info("카테고리 = {}", categoryName.getKorName());
            }
        }
    }

    // articleId를 이용해 새로운 공지인지 아닌지 판별한다.
    private List<Notice> compareAndUpdateDB(List<CommonNoticeFormatDTO> commonNoticeFormatDTOList, CategoryName categoryName) {

        List<Notice> newNotices = new LinkedList<>();
        Category category = categoryMap.get(categoryName.getName());
        Map<String, Notice> dbNoticesMap = noticeRepository.findByCategoryMap(category);
        for (CommonNoticeFormatDTO notice : commonNoticeFormatDTOList) {
            try {
                // db에 해당 articleId와 categoryName과 동일한 공지가 없다면, 새 공지로 인식한다.
                if(dbNoticesMap.get(notice.getArticleId()) == null) {
                    Notice newNotice = commonNoticeFormatDTOToNoticeEntityConverter.convert(notice);
                    newNotice.setCategory(category);
                    newNotices.add(newNotice);
                }
            } catch(IncorrectResultSizeDataAccessException e) {
                log.error("오류가 발생한 공지 정보");
                log.error("articleId = {}", notice.getArticleId());
                log.error("postedDate = {}", notice.getPostedDate());
                log.error("subject = {}", notice.getSubject());
            }
        }

        // 업데이트로 인해 새로 생성된 공지 삽입
        noticeRepository.saveAllAndFlush(newNotices);

        return newNotices;
    }
}