package roles

func Catalog() []map[string]any {
	return []map[string]any{
		{"roleCode": "PRODUCT_OWNER", "roleName": "Product Owner", "required": true, "displayOrder": 1},
		{"roleCode": "PROJECT_MANAGER", "roleName": "Project Manager", "required": true, "displayOrder": 2},
		{"roleCode": "TECH_LEAD", "roleName": "Tech Lead", "required": true, "displayOrder": 3},
		{"roleCode": "QA_LEAD", "roleName": "QA Lead", "required": false, "displayOrder": 4},
		{"roleCode": "BUSINESS_ANALYST", "roleName": "Business Analyst", "required": false, "displayOrder": 5},
		{"roleCode": "DESIGNER", "roleName": "Designer", "required": false, "displayOrder": 6},
		{"roleCode": "DEVOPS", "roleName": "DevOps", "required": false, "displayOrder": 7},
		{"roleCode": "STAKEHOLDER", "roleName": "Stakeholder", "required": false, "displayOrder": 8},
	}
}
